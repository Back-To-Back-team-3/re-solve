package com.backtoback.notification.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.delivery.service.NotificationDeliveryService;
import com.backtoback.notification.event.domain.ProcessedEvent;
import com.backtoback.notification.event.dto.MemberWithdrawnCleanupEvent;
import com.backtoback.notification.event.dto.NotificationCreatedEvent;
import com.backtoback.notification.event.repository.ProcessedEventRepository;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;
import com.backtoback.notification.reminder.service.ExamReminderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 수신한 도메인 이벤트를 멱등하게 기록하고 알림 엔티티로 변환하여 저장한 뒤,
 * 트랜잭션 커밋 이후 SSE 실시간 전송을 수행하는 서비스 (이벤트 계약서 1.4, 5.1, 6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventProcessor {

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationDeliveryService deliveryService;
    private final ApplicationEventPublisher eventPublisher;
    private final ExamReminderService examReminderService;
    private final Clock clock;

    /**
     * 도메인 이벤트를 단일 트랜잭션에서 멱등 처리하고 알림을 생성한다 (이벤트 계약서 1.4, 5.1).
     * 이미 처리된 이벤트는 건너뛰며, 알 수 없는 이벤트 타입은 {@code processed_events}만 기록하고 종료한다.
     *
     * @param eventId 원천 이벤트 ID
     * @param eventType 도메인 이벤트 타입
     * @param payload 이벤트 JSON 페이로드
     */
    @Transactional
    public void process(String eventId, String eventType, JsonNode payload) {
        if (processedEventRepository.existsByEventId(eventId)) {
            log.debug("이미 처리된 중복 이벤트 무시: eventId={}, eventType={}", eventId, eventType);
            return;
        }

        processedEventRepository.saveAndFlush(ProcessedEvent.create(eventId, eventType, LocalDateTime.now(clock)));

        handleReminderSideEffects(eventId, eventType, payload);

        NotificationSpec spec = resolveSpec(eventType, payload);
        if (spec == null) {
            log.debug("알림 생성 대상이 아닌 이벤트 기록 완료: eventId={}, eventType={}", eventId, eventType);
            return;
        }

        Set<Long> withdrawnMemberIds = examReminderService.findWithdrawnMemberIds(spec.memberIds());
        for (Long memberId : spec.memberIds()) {
            if (withdrawnMemberIds.contains(memberId)) {
                log.debug("탈퇴 회원 수신자 제외로 알림 생성 생략: memberId={}, type={}", memberId, spec.type());
                continue;
            }

            if (!isPreferenceEnabled(memberId, spec.preferenceFilter())) {
                log.debug("수신 설정 비활성화로 알림 생성 생략: memberId={}, type={}", memberId, spec.type());
                continue;
            }

            if (notificationRepository.existsBySourceEventIdAndMemberIdAndType(eventId, memberId, spec.type())) {
                log.debug("동일 원천 알림 중복 생성 생략: eventId={}, memberId={}, type={}", eventId, memberId, spec.type());
                continue;
            }

            Notification notification
                = Notification
                    .builder()
                    .memberId(memberId)
                    .sourceEventId(eventId)
                    .type(spec.type())
                    .title(spec.title())
                    .content(spec.content())
                    .linkUrl(spec.linkUrl())
                    .build();

            Notification savedNotification = notificationRepository.saveAndFlush(notification);
            eventPublisher.publishEvent(new NotificationCreatedEvent(memberId, savedNotification));
        }
    }

    /**
     * 알림 저장 트랜잭션이 커밋된 이후 활성 SSE 세션으로 실시간 푸시를 전송한다 (이벤트 계약서 5.1).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            deliveryService.sendToMember(event.memberId(), event.notification());
        } catch (Exception exception) {
            // SSE 전송 실패가 이미 커밋된 알림 저장 상태나 메시지 소비 성공에 영향을 주지 않도록 격리한다 (정책 및 상태 §9.2).
            log
                .warn(
                    "커밋 후 SSE 실시간 알림 전송 중 예외 발생: memberId={}, notificationId={}",
                    event.memberId(),
                    event.notification().getId(),
                    exception
                );
        }
    }

    /**
     * 회원 탈퇴({@code MemberWithdrawn}) 데이터 삭제 트랜잭션이 커밋된 이후 해당 회원의 활성 SSE 연결을 모두 종료한다 (이벤트 계약서 4.1 M3, 5.1).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberWithdrawn(MemberWithdrawnCleanupEvent event) {
        try {
            deliveryService.disconnectMember(event.memberId());
        } catch (Exception exception) {
            log.warn("커밋 후 탈퇴 회원 SSE 연결 종료 중 예외 발생: memberId={}", event.memberId(), exception);
        }
    }

    private void handleReminderSideEffects(String eventId, String eventType, JsonNode payload) {
        switch (eventType) {
            case "ExamParticipantRegistered" -> handleExamParticipantRegistered(eventId, payload);
            case "ExamUpdated" -> handleExamUpdatedReminder(eventId, payload);
            case "ExamParticipantCanceled" -> handleExamParticipantCanceled(eventId, payload);
            case "ExamCanceled" -> handleExamCanceledReminder(eventId, payload);
            case "MemberWithdrawn" -> handleMemberWithdrawn(eventId, payload);
            default -> {
                // 예약 알림 및 회원 탈퇴 부수효과 대상이 아님
            }
        }
    }

    private void handleMemberWithdrawn(String eventId, JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        int sourceVersion = requireInt(payload, "sourceVersion");
        LocalDateTime withdrawnAt = requireDateTime(payload, "withdrawnAt");

        examReminderService.cleanupByMemberWithdrawn(eventId, memberId, sourceVersion, withdrawnAt);
        notificationRepository.deleteAllByMemberId(memberId);
        preferenceRepository.deleteAllByMemberId(memberId);
        eventPublisher.publishEvent(new MemberWithdrawnCleanupEvent(memberId));
    }

    private void handleExamParticipantRegistered(String eventId, JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        Long examId = requireLong(payload, "examId");
        Long participantId = requireLong(payload, "participantId");
        int contextRevision = requireParsedInt(payload, "contextRevision");
        LocalDateTime startsAt = requireDateTime(payload, "startsAt");

        examReminderService.registerReminder(eventId, memberId, examId, participantId, contextRevision, startsAt);
    }

    private void handleExamUpdatedReminder(String eventId, JsonNode payload) {
        Long examId = requireLong(payload, "examId");
        int examRevision = requireParsedInt(payload, "examRevision");
        LocalDateTime startsAt = requireDateTime(payload, "startsAt");

        examReminderService.rescheduleByExamUpdated(eventId, examId, examRevision, startsAt);
    }

    private void handleExamParticipantCanceled(String eventId, JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        Long examId = requireLong(payload, "examId");
        Long participantId = requireLong(payload, "participantId");
        int contextRevision = requireParsedInt(payload, "contextRevision");
        LocalDateTime canceledAt = requireDateTime(payload, "canceledAt");

        examReminderService
            .cancelByParticipantCanceled(eventId, memberId, examId, participantId, contextRevision, canceledAt);
    }

    private void handleExamCanceledReminder(String eventId, JsonNode payload) {
        Long examId = requireLong(payload, "examId");
        int examRevision = requireParsedInt(payload, "examRevision");
        LocalDateTime canceledAt = requireDateTime(payload, "canceledAt");
        examReminderService.cancelByExamCanceled(eventId, examId, examRevision, canceledAt);
    }

    private boolean isPreferenceEnabled(Long memberId, Predicate<NotificationPreference> preferenceFilter) {
        if (preferenceFilter == null) {
            return true;
        }
        return preferenceRepository.findByMemberId(memberId).map(preferenceFilter::test).orElse(true);
    }

    private NotificationSpec resolveSpec(String eventType, JsonNode payload) {
        return switch (eventType) {
            case "SubmissionJudged" -> buildSubmissionJudgedSpec(payload);
            case "SubmissionFailed" -> buildSubmissionFailedSpec(payload);
            case "HintReady" -> buildHintReadySpec(payload);
            case "TagLevelChanged" -> buildTagLevelChangedSpec(payload);
            case "StudyApplicationDecided" -> buildStudyApplicationDecidedSpec(payload);
            case "AssignmentPublished" -> buildAssignmentPublishedSpec(payload);
            case "AssignmentDeadlineApproaching" -> buildAssignmentDeadlineApproachingSpec(payload);
            case "AssignmentIncompleteRepeated" -> buildAssignmentIncompleteRepeatedSpec(payload);
            case "ExamUpdated" -> buildExamUpdatedSpec(payload);
            case "ExamCanceled" -> buildExamCanceledSpec(payload);
            case "ExamFinalized" -> buildExamFinalizedSpec(payload);
            case "ContestStarted" -> buildContestStartedSpec(payload);
            case "ContestCanceled" -> buildContestCanceledSpec(payload);
            case "ContestFinalized" -> buildContestFinalizedSpec(payload);
            case "GitHubSyncFailed" -> buildGithubSyncFailedSpec(payload);
            default -> null;
        };
    }

    private NotificationSpec buildSubmissionJudgedSpec(JsonNode payload) {
        Long userId = requireLong(payload, "userId");
        String submissionId = requireText(payload, "submissionId");
        String problemId = requireText(payload, "problemId");
        String verdict = requireText(payload, "verdict");

        return new NotificationSpec(
            List.of(userId),
            NotificationType.SUBMISSION_JUDGED,
            "채점 완료",
            problemId + "번 문제 채점이 완료되었습니다. (결과: " + verdict + ")",
            "/submissions/" + submissionId,
            null
        );
    }

    private NotificationSpec buildSubmissionFailedSpec(JsonNode payload) {
        Long userId = requireLong(payload, "userId");
        String submissionId = requireText(payload, "submissionId");
        String problemId = requireText(payload, "problemId");
        String context = optionalText(payload, "context");
        String contextId = optionalText(payload, "contextId");

        String linkUrl = "/submissions/" + submissionId;
        if ("EXAM".equals(context) && contextId != null) {
            linkUrl = "/exams/" + contextId;
        } else if ("CONTEST".equals(context) && contextId != null) {
            linkUrl = "/contests/" + contextId;
        }

        return new NotificationSpec(
            List.of(userId),
            NotificationType.SUBMISSION_FAILED,
            "채점 실패",
            problemId + "번 문제 채점 실패, 재처리 예정입니다.",
            linkUrl,
            null
        );
    }

    private NotificationSpec buildHintReadySpec(JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        String problemId = requireText(payload, "problemId");
        String hintRequestId = requireText(payload, "hintRequestId");
        int level = requireInt(payload, "level");
        String result = requireText(payload, "result");

        String title = "힌트 준비 완료";
        String content = problemId + "번 문제의 " + level + "단계 힌트가 준비되었습니다.";
        if ("FALLBACK".equals(result)) {
            content = problemId + "번 문제의 " + level + "단계 기본 힌트가 준비되었습니다.";
        } else if ("BLOCKED".equals(result)) {
            title = "힌트 요청 제한";
            content = "시험 또는 진단 응시 중에는 힌트를 확인할 수 없습니다.";
        }

        return new NotificationSpec(
            List.of(memberId),
            NotificationType.HINT_READY,
            title,
            content,
            "/problems/" + problemId + "?hint=" + hintRequestId,
            NotificationPreference::isHintReadyEnabled
        );
    }

    private NotificationSpec buildTagLevelChangedSpec(JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        String tagId = requireText(payload, "tagId");
        String tagName = requireText(payload, "tagName");
        String toLevel = requireText(payload, "toLevel");
        String reasonType = requireText(payload, "reasonType");

        String formattedLevel = formatLevel(toLevel);
        String content = switch (reasonType) {
            case "DIAGNOSIS" -> tagName + " 진단 결과: " + formattedLevel + "로 시작합니다.";
            case "PROMOTION" ->
                "MASTER".equals(toLevel) ? tagName + " 마스터 달성! 배지를 획득했습니다." : tagName + " " + formattedLevel + " 달성!";
            case "REJUDGE" -> "채점 오류 정정으로 " + tagName + " 레벨이 " + formattedLevel + "로 조정되었습니다.";
            case "COOLDOWN_RELEASED" -> tagName + " 진단 테스트를 다시 응시할 수 있습니다.";
            default -> tagName + " 레벨이 " + formattedLevel + "로 변경되었습니다.";
        };

        return new NotificationSpec(
            List.of(memberId),
            NotificationType.LEVEL_CHANGED,
            "태그 레벨 알림",
            content,
            "/me/tag-levels/" + tagId,
            NotificationPreference::isLevelChangedEnabled
        );
    }

    private NotificationSpec buildStudyApplicationDecidedSpec(JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        String studyId = requireText(payload, "studyId");
        String result = requireText(payload, "result");

        String content = switch (result) {
            case "APPROVED" -> "스터디 가입 신청이 승인되었습니다.";
            case "REJECTED" -> "스터디 가입 신청이 거절되었습니다.";
            default -> "스터디 가입 신청이 만료되었습니다.";
        };

        return new NotificationSpec(
            List.of(memberId),
            NotificationType.STUDY_APPLICATION_DECIDED,
            "스터디 가입 결과 안내",
            content,
            "/studies/" + studyId,
            NotificationPreference::isStudyApplicationEnabled
        );
    }

    private NotificationSpec buildAssignmentPublishedSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");
        String studyId = requireText(payload, "studyId");
        String assignmentId = requireText(payload, "assignmentId");
        String title = requireText(payload, "title");

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.ASSIGNMENT_PUBLISHED,
            "새 문제집 배정",
            "[" + title + "] 문제집이 배정되었습니다.",
            "/studies/" + studyId + "/assignments/" + assignmentId,
            NotificationPreference::isAssignmentEnabled
        );
    }

    private NotificationSpec buildAssignmentDeadlineApproachingSpec(JsonNode payload) {
        List<Long> incompleteMemberIds = requireLongList(payload, "incompleteMemberIds");
        String studyId = requireText(payload, "studyId");
        String assignmentId = requireText(payload, "assignmentId");
        String title = requireText(payload, "title");

        return new NotificationSpec(
            incompleteMemberIds,
            NotificationType.ASSIGNMENT_DEADLINE_APPROACHING,
            "문제집 마감 임박",
            "[" + title + "] 문제집 마감이 24시간 이내로 다가왔습니다.",
            "/studies/" + studyId + "/assignments/" + assignmentId,
            NotificationPreference::isAssignmentEnabled
        );
    }

    private NotificationSpec buildAssignmentIncompleteRepeatedSpec(JsonNode payload) {
        List<Long> recipientMemberIds = requireLongList(payload, "recipientMemberIds");
        String studyId = requireText(payload, "studyId");

        return new NotificationSpec(
            recipientMemberIds,
            NotificationType.ASSIGNMENT_INCOMPLETE_REPEATED,
            "문제집 연속 미완료 발생",
            "스터디 내 연속 2회 과제를 미완료한 구성원이 있습니다.",
            "/studies/" + studyId + "/manage",
            NotificationPreference::isAssignmentEnabled
        );
    }

    private NotificationSpec buildExamUpdatedSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");
        String examId = requireText(payload, "examId");

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.EXAM_UPDATED,
            "모의 코딩테스트 설정 변경",
            "참가 등록한 모의 코딩테스트의 일정 또는 문제 구성이 변경되었습니다.",
            "/exams/" + examId,
            NotificationPreference::isExamContestEnabled
        );
    }

    private NotificationSpec buildExamCanceledSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");
        String studyId = requireText(payload, "studyId");

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.EXAM_CANCELED,
            "모의 코딩테스트 취소",
            "예정된 모의 코딩테스트가 취소되었습니다.",
            "/studies/" + studyId,
            NotificationPreference::isExamContestEnabled
        );
    }

    private NotificationSpec buildExamFinalizedSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");
        String examId = requireText(payload, "examId");
        boolean refinalized = payload.path("isRefinalized").asBoolean(false);

        String content = refinalized ? "모의 코딩테스트 결과가 재확정되었습니다." : "모의 코딩테스트 최종 결과가 공개되었습니다.";

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.EXAM_FINALIZED,
            "모의 코딩테스트 결과 확정",
            content,
            "/exams/" + examId + "/result",
            NotificationPreference::isExamContestEnabled
        );
    }

    private NotificationSpec buildContestStartedSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");
        String contestId = requireText(payload, "contestId");

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.CONTEST_STARTED,
            "대회 시작",
            "참가 등록한 공개 대회가 시작되었습니다.",
            "/contests/" + contestId,
            NotificationPreference::isExamContestEnabled
        );
    }

    private NotificationSpec buildContestCanceledSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.CONTEST_CANCELED,
            "대회 취소",
            "예정된 공개 대회가 취소되었습니다.",
            "/contests",
            NotificationPreference::isExamContestEnabled
        );
    }

    private NotificationSpec buildContestFinalizedSpec(JsonNode payload) {
        List<Long> targetMemberIds = requireLongList(payload, "targetMemberIds");
        String contestId = requireText(payload, "contestId");
        boolean refinalized = payload.path("isRefinalized").asBoolean(false);

        String content = refinalized ? "공개 대회 최종 순위가 재확정되었습니다." : "공개 대회 최종 순위가 확정되었습니다.";

        return new NotificationSpec(
            targetMemberIds,
            NotificationType.CONTEST_FINALIZED,
            "대회 결과 확정",
            content,
            "/contests/" + contestId + "/standings",
            NotificationPreference::isExamContestEnabled
        );
    }

    private NotificationSpec buildGithubSyncFailedSpec(JsonNode payload) {
        Long memberId = requireLong(payload, "memberId");
        String errorCode = requireText(payload, "errorCode");

        String content
            = "AUTH_EXPIRED".equals(errorCode) ? "GitHub 연동 권한이 만료되어 자동 커밋에 실패했습니다. 다시 연동해 주세요."
                : "GitHub 저장소 자동 커밋 동기화에 실패했습니다.";

        return new NotificationSpec(
            List.of(memberId),
            NotificationType.GITHUB_SYNC_FAILED,
            "GitHub 동기화 실패",
            content,
            "/settings/github",
            NotificationPreference::isGithubSyncEnabled
        );
    }

    private String formatLevel(String levelCode) {
        if (levelCode != null && levelCode.startsWith("LV")) {
            return "Lv." + levelCode.substring(2);
        }
        return levelCode;
    }

    private String requireText(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("필수 이벤트 필드가 누락되었습니다: " + fieldName);
        }
        String value = node.asString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("필수 이벤트 필드가 비어 있습니다: " + fieldName);
        }
        return value;
    }

    private String optionalText(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    private Long requireLong(JsonNode payload, String fieldName) {
        String text = requireText(payload, fieldName);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("숫자 식별자 형식이 올바르지 않습니다: " + fieldName, exception);
        }
    }

    private int requireInt(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("정수 필드 형식이 올바르지 않습니다: " + fieldName);
        }
        return node.asInt();
    }

    private int requireParsedInt(JsonNode payload, String fieldName) {
        String text = requireText(payload, fieldName);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("정수 버전 형식이 올바르지 않습니다: " + fieldName, exception);
        }
    }

    private LocalDateTime requireDateTime(JsonNode payload, String fieldName) {
        String text = requireText(payload, fieldName);
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ClockConfiguration.SERVICE_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("시각 필드 형식이 올바르지 않습니다: " + fieldName, exception);
            }
        }
    }

    private List<Long> requireLongList(JsonNode payload, String fieldName) {
        JsonNode arrayNode = payload.get(fieldName);
        if (arrayNode == null || !arrayNode.isArray()) {
            throw new IllegalArgumentException("목록 필드가 배열 형식이 아닙니다: " + fieldName);
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            try {
                result.add(Long.parseLong(element.asString().trim()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("목록 내 식별자 형식이 올바르지 않습니다: " + fieldName, exception);
            }
        }
        return result;
    }

    private record NotificationSpec(
        List<Long> memberIds,
        NotificationType type,
        String title,
        String content,
        String linkUrl,
        Predicate<NotificationPreference> preferenceFilter
    ) {
    }
}

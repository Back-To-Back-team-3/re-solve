package com.backtoback.notification.reminder.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.notification.event.dto.NotificationCreatedEvent;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.preference.domain.NotificationPreference;
import com.backtoback.notification.preference.repository.NotificationPreferenceRepository;
import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.domain.ReminderStatus;
import com.backtoback.notification.reminder.repository.NotificationReminderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시험 시작 5분 전 임박 예약 알림의 등록·갱신·취소 및 발화 처리를 담당하는 서비스 (스케줄러 명세서 7.1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamReminderService {

    private static final Long SENTINEL_MEMBER_ID = 0L;
    private static final String EXAM_CANCELED_SOURCE_PREFIX = "EXAM_CANCELED:";
    public static final int SINGLE_OPERATION_TIMEOUT_SECONDS = 1;

    private final NotificationReminderRepository reminderRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * {@code ExamParticipantRegistered} 이벤트 수신 시 시험 시작 5분 전 예약 알림을 등록한다.
     * 이벤트 순서 역전으로 {@code ExamCanceled} 또는 더 높은 버전의 {@code ExamUpdated}가 먼저 처리된 경우
     * 저장된 시험 단위 취소 종단 표식과 최신 스케줄 스냅샷을 우선 적용하여 잘못된 시각 발화를 차단한다 (이벤트 계약서 1.5, 스케줄러 명세서 7.1).
     * 상위 이벤트 트랜잭션이 {@code DataIntegrityViolationException}으로 {@code rollback-only}가 되지 않도록
     * 무예외 {@code INSERT IGNORE} 쿼리를 사용한다.
     *
     * @param sourceEventId 원천 이벤트 ID
     * @param memberId 참가 회원 ID
     * @param examId 시험 ID
     * @param participantId 참가자 ID
     * @param contextRevision 등록 시점 시험 설정 버전
     * @param startsAt 시험 시작 시각
     */
    @Transactional
    public void registerReminder(
        String sourceEventId,
        Long memberId,
        Long examId,
        Long participantId,
        int contextRevision,
        LocalDateTime startsAt
    ) {
        LocalDateTime now = currentDateTime();
        Long canceledSentinelId = examCanceledTombstoneParticipantId(examId);
        if (reminderRepository.existsByParticipantId(canceledSentinelId)) {
            reminderRepository
                .insertIgnoreReminder(
                    memberId,
                    examId,
                    participantId,
                    startsAt.minusMinutes(5),
                    startsAt,
                    contextRevision,
                    sourceEventId,
                    ReminderStatus.CANCELED.name(),
                    now
                );
            return;
        }

        LocalDateTime effectiveStartsAt = startsAt;
        int effectiveRevision = contextRevision;
        String effectiveSourceEventId = sourceEventId;

        Optional<NotificationReminder> snapshotOpt
            = reminderRepository.findByParticipantId(examScheduleSnapshotParticipantId(examId));
        if (snapshotOpt.isPresent() && snapshotOpt.get().getContextRevision() > contextRevision) {
            NotificationReminder snapshot = snapshotOpt.get();
            effectiveStartsAt = snapshot.getStartsAt();
            effectiveRevision = snapshot.getContextRevision();
            effectiveSourceEventId = snapshot.getSourceEventId();
        }

        boolean isFutureStart = effectiveStartsAt.isAfter(now);
        LocalDateTime fireAt = isFutureStart ? effectiveStartsAt.minusMinutes(5) : effectiveStartsAt;
        ReminderStatus initialStatus = isFutureStart ? ReminderStatus.SCHEDULED : ReminderStatus.CANCELED;

        int inserted
            = reminderRepository
                .insertIgnoreReminder(
                    memberId,
                    examId,
                    participantId,
                    fireAt,
                    effectiveStartsAt,
                    effectiveRevision,
                    effectiveSourceEventId,
                    initialStatus.name(),
                    now
                );
        if (inserted == 0) {
            log.debug("이미 등록되었거나 취소 표식이 존재하는 참가 예약 무시: participantId={}", participantId);
            return;
        }

        if (initialStatus == ReminderStatus.SCHEDULED && reminderRepository.existsByParticipantId(canceledSentinelId)) {
            reminderRepository.cancelScheduledByParticipantId(participantId, now);
        }
    }

    /**
     * {@code ExamUpdated} 이벤트 수신 시 시험 단위 최신 스케줄 스냅샷을 먼저 기록한 뒤,
     * 해당 시험의 {@link ReminderStatus#SCHEDULED} 예약 중 수신한 {@code examRevision}보다 낮은 버전의 예약만 조건부 UPDATE로 갱신한다 (스케줄러 명세서
     * 7.1, 이벤트 계약서 5.1).
     * 변경된 시작 시각이 이미 현재 시각 이하이면 발화하지 않도록 {@link ReminderStatus#CANCELED}로 전이한다.
     *
     * @param sourceEventId ExamUpdated 이벤트 ID
     * @param examId 시험 ID
     * @param examRevision 변경된 시험 설정 버전
     * @param newStartsAt 변경된 시험 시작 시각
     */
    @Transactional
    public void rescheduleByExamUpdated(
        String sourceEventId,
        Long examId,
        int examRevision,
        LocalDateTime newStartsAt
    ) {
        LocalDateTime now = currentDateTime();
        LocalDateTime newFireAt = newStartsAt.minusMinutes(5);
        Long snapshotParticipantId = examScheduleSnapshotParticipantId(examId);

        int snapshotInserted
            = reminderRepository
                .insertIgnoreReminder(
                    SENTINEL_MEMBER_ID,
                    examId,
                    snapshotParticipantId,
                    newFireAt,
                    newStartsAt,
                    examRevision,
                    sourceEventId,
                    ReminderStatus.CANCELED.name(),
                    now
                );
        if (snapshotInserted == 0) {
            reminderRepository
                .updateExamSnapshotIfNewer(
                    snapshotParticipantId,
                    examRevision,
                    newStartsAt,
                    newFireAt,
                    sourceEventId,
                    now
                );
        }

        if (!newStartsAt.isAfter(now)) {
            reminderRepository.cancelScheduledByExamIdAndNewerRevision(examId, examRevision, sourceEventId, now);
            return;
        }

        reminderRepository
            .rescheduleScheduledByExamId(examId, examRevision, newStartsAt, newFireAt, sourceEventId, now);
    }

    /**
     * {@code ExamParticipantCanceled} 이벤트 수신 시 해당 참가자의 예약을 조건부 UPDATE로 취소한다.
     * 등록 이벤트보다 취소 이벤트가 먼저 도착해 행이 없는 경우 무예외 {@code INSERT IGNORE}로 취소 표식({@link ReminderStatus#CANCELED})을 삽입하며,
     * 삽입 시점에 동시 등록이 먼저 커밋되어 0건이 반환되면 즉시 조건부 취소 UPDATE를 재실행하여 취소 상태를 보장한다.
     *
     * @param sourceEventId 원천 이벤트 ID
     * @param memberId 회원 ID
     * @param examId 시험 ID
     * @param participantId 참가자 ID
     * @param contextRevision 시험 설정 버전
     * @param canceledAt 취소 시각
     */
    @Transactional
    public void cancelByParticipantCanceled(
        String sourceEventId,
        Long memberId,
        Long examId,
        Long participantId,
        int contextRevision,
        LocalDateTime canceledAt
    ) {
        LocalDateTime now = currentDateTime();
        int updated = reminderRepository.cancelScheduledByParticipantId(participantId, now);
        if (updated > 0) {
            return;
        }

        int inserted
            = reminderRepository
                .insertIgnoreReminder(
                    memberId,
                    examId,
                    participantId,
                    canceledAt,
                    canceledAt,
                    contextRevision,
                    sourceEventId,
                    ReminderStatus.CANCELED.name(),
                    now
                );
        if (inserted == 0) {
            reminderRepository.cancelScheduledByParticipantId(participantId, now);
        }
    }

    /**
     * {@code ExamCanceled} 이벤트 수신 시 지연 등록 차단을 위한 시험 취소 종단 표식을 기록하고,
     * 해당 시험의 발화 대기 예약을 일괄 취소한다.
     *
     * @param examId 취소된 시험 ID
     */
    @Transactional
    public void cancelByExamCanceled(Long examId) {
        LocalDateTime now = currentDateTime();
        reminderRepository
            .insertIgnoreReminder(
                SENTINEL_MEMBER_ID,
                examId,
                examCanceledTombstoneParticipantId(examId),
                now,
                now,
                Integer.MAX_VALUE,
                EXAM_CANCELED_SOURCE_PREFIX + examId,
                ReminderStatus.CANCELED.name(),
                now
            );
        reminderRepository.cancelAllScheduledByExamId(examId, now);
    }

    /**
     * 이미 시험 시작 시각({@code starts_at <= now})이 지난 발화 대기 예약을 발화하지 않고 일괄 취소한다 (스케줄러 명세서 7.1).
     *
     * @return 취소된 예약 건수
     */
    @Transactional(timeout = SINGLE_OPERATION_TIMEOUT_SECONDS)
    public int cancelExpiredReminders() {
        return reminderRepository.cancelExpiredScheduled(currentDateTime());
    }

    /**
     * 현재 시각 기준으로 발화 시각이 도래하고 아직 시작 시각 전인 {@link ReminderStatus#SCHEDULED} 예약 목록을 조회한다.
     *
     * @param limit 한 회차 조회 상한 건수
     * @return 발화 대상 예약 목록
     */
    @Transactional(
        readOnly = true,
        timeout = SINGLE_OPERATION_TIMEOUT_SECONDS
    )
    public List<NotificationReminder> findDueReminders(int limit) {
        LocalDateTime now = currentDateTime();
        return reminderRepository
            .findByStatusAndFireAtLessThanEqualAndStartsAtGreaterThanOrderByFireAtAsc(
                ReminderStatus.SCHEDULED,
                now,
                now,
                PageRequest.of(0, limit)
            );
    }

    /**
     * 예약 1건을 독립 트랜잭션(최대 1초 타임아웃)에서 조건부 {@code SENT} 전이 후 {@code EXAM_STARTING_SOON} 알림으로 저장한다 (스케줄러 명세서 0.1, 7.1).
     * 조회 이후 {@code ExamUpdated}로 {@code fire_at}이 미래로 연기된 경우 조건부 UPDATE({@code fire_at <= now})에서 0건으로 걸러진다.
     * 발화 시점에 회원의 시험·대회 수신 설정이 꺼져 있으면 예약만 {@code SENT}로 전이하고 알림은 생성하지 않는다.
     *
     * @param reminder 발화 대상 예약
     * @return 실제 알림이 생성되었으면 {@code true}, 이미 처리·연기되었거나 수신 거부 시 {@code false}
     */
    @Transactional(timeout = SINGLE_OPERATION_TIMEOUT_SECONDS)
    public boolean dispatchSingleReminder(NotificationReminder reminder) {
        LocalDateTime now = currentDateTime();
        int updated = reminderRepository.markAsSentIfScheduled(reminder.getId(), now);
        if (updated == 0) {
            return false;
        }

        boolean enabled
            = preferenceRepository
                .findByMemberId(reminder.getMemberId())
                .map(NotificationPreference::isExamContestEnabled)
                .orElse(true);

        if (!enabled) {
            log.debug("수신 설정 비활성화로 시험 임박 알림 미생성 (SENT 처리만 완료): memberId={}", reminder.getMemberId());
            return false;
        }

        if (notificationRepository
            .existsBySourceEventIdAndMemberIdAndType(
                reminder.getSourceEventId(),
                reminder.getMemberId(),
                NotificationType.EXAM_STARTING_SOON
            )) {
            return false;
        }

        Notification notification
            = Notification
                .builder()
                .memberId(reminder.getMemberId())
                .sourceEventId(reminder.getSourceEventId())
                .type(NotificationType.EXAM_STARTING_SOON)
                .title("모의 코딩테스트 시작 임박")
                .content("모의 코딩테스트 시작 5분 전입니다.")
                .linkUrl("/exams/" + reminder.getExamId())
                .build();

        Notification saved = notificationRepository.saveAndFlush(notification);
        eventPublisher.publishEvent(new NotificationCreatedEvent(reminder.getMemberId(), saved));
        return true;
    }

    private LocalDateTime currentDateTime() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    static Long examScheduleSnapshotParticipantId(Long examId) {
        return -(examId * 2L);
    }

    static Long examCanceledTombstoneParticipantId(Long examId) {
        return -(examId * 2L - 1L);
    }
}

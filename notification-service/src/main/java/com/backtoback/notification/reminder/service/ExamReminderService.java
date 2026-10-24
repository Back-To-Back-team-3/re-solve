package com.backtoback.notification.reminder.service;

import java.time.Clock;
import java.time.LocalDateTime;
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

    private final NotificationReminderRepository reminderRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * {@code ExamParticipantRegistered} 이벤트 수신 시 시험 시작 5분 전 예약 알림을 등록한다.
     * 이미 동일한 {@code participantId}의 행(중복 등록 또는 선행 취소 표식)이 있으면 무시하며,
     * 시작 시각이 이미 지난 경우에도 발화하지 않도록 {@link ReminderStatus#CANCELED}로 기록한다.
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
        if (reminderRepository.findByParticipantId(participantId).isPresent()) {
            log.debug("이미 등록되었거나 취소 표식이 존재하는 참가 예약 무시: participantId={}", participantId);
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!startsAt.isAfter(now)) {
            reminderRepository
                .saveAndFlush(
                    NotificationReminder
                        .createCanceledTombstone(
                            memberId,
                            examId,
                            participantId,
                            startsAt,
                            contextRevision,
                            sourceEventId
                        )
                );
            return;
        }

        NotificationReminder reminder
            = NotificationReminder
                .createScheduled(memberId, examId, participantId, startsAt, contextRevision, sourceEventId);
        reminderRepository.saveAndFlush(reminder);
    }

    /**
     * {@code ExamUpdated} 이벤트 수신 시 해당 시험의 {@link ReminderStatus#SCHEDULED} 예약 중
     * 수신한 {@code examRevision}보다 낮은 버전의 예약만 새 시작 시각({@code startsAt - 5분})으로 갱신한다.
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
        List<NotificationReminder> scheduledReminders
            = reminderRepository.findByExamIdAndStatus(examId, ReminderStatus.SCHEDULED);

        for (NotificationReminder reminder : scheduledReminders) {
            reminder.rescheduleIfNewer(newStartsAt, examRevision, sourceEventId);
        }
        if (!scheduledReminders.isEmpty()) {
            reminderRepository.saveAllAndFlush(scheduledReminders);
        }
    }

    /**
     * {@code ExamParticipantCanceled} 이벤트 수신 시 해당 참가자의 예약을 취소한다.
     * 등록 이벤트보다 취소 이벤트가 먼저 도착한 경우 취소 표식({@link ReminderStatus#CANCELED}) 행을 생성하여
     * 뒤늦게 도착하는 등록 이벤트를 차단한다.
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
        Optional<NotificationReminder> existing = reminderRepository.findByParticipantId(participantId);
        if (existing.isPresent()) {
            NotificationReminder reminder = existing.get();
            reminder.cancel();
            reminderRepository.saveAndFlush(reminder);
            return;
        }

        NotificationReminder tombstone
            = NotificationReminder
                .createCanceledTombstone(memberId, examId, participantId, canceledAt, contextRevision, sourceEventId);
        reminderRepository.saveAndFlush(tombstone);
    }

    /**
     * {@code ExamCanceled} 이벤트 수신 시 해당 시험의 발화 대기 예약을 일괄 취소한다.
     *
     * @param examId 취소된 시험 ID
     */
    @Transactional
    public void cancelByExamCanceled(Long examId) {
        reminderRepository.cancelAllScheduledByExamId(examId, LocalDateTime.now(clock));
    }

    /**
     * 이미 시험 시작 시각({@code starts_at <= now})이 지난 발화 대기 예약을 발화하지 않고 일괄 취소한다 (스케줄러 명세서 7.1).
     *
     * @return 취소된 예약 건수
     */
    @Transactional
    public int cancelExpiredReminders() {
        return reminderRepository.cancelExpiredScheduled(LocalDateTime.now(clock));
    }

    /**
     * 현재 시각 기준으로 발화 시각이 도래하고 아직 시작 시각 전인 {@link ReminderStatus#SCHEDULED} 예약 목록을 조회한다.
     *
     * @param limit 한 회차 조회 상한 건수
     * @return 발화 대상 예약 목록
     */
    @Transactional(readOnly = true)
    public List<NotificationReminder> findDueReminders(int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        return reminderRepository
            .findByStatusAndFireAtLessThanEqualAndStartsAtGreaterThanOrderByFireAtAsc(
                ReminderStatus.SCHEDULED,
                now,
                now,
                PageRequest.of(0, limit)
            );
    }

    /**
     * 예약 1건을 독립 트랜잭션에서 조건부 {@code SENT} 전이 후 {@code EXAM_STARTING_SOON} 알림으로 저장한다 (스케줄러 명세서 0.1, 7.1).
     * 발화 시점에 회원의 시험·대회 수신 설정이 꺼져 있으면 예약만 {@code SENT}로 전이하고 알림은 생성하지 않는다.
     *
     * @param reminder 발화 대상 예약
     * @return 실제 알림이 생성되었으면 {@code true}, 이미 처리되었거나 수신 거부 시 {@code false}
     */
    @Transactional
    public boolean dispatchSingleReminder(NotificationReminder reminder) {
        LocalDateTime now = LocalDateTime.now(clock);
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
}

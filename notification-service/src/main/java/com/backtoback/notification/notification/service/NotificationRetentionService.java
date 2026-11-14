package com.backtoback.notification.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.reminder.repository.NotificationReminderRepository;

import lombok.RequiredArgsConstructor;

/**
 * 알림 도메인 만료 데이터(90일 경과 알림, 7일 경과 종결 참가자 예약, 14일 경과 음수 표식 행)를
 * 배치 단위 독립 트랜잭션으로 물리 삭제하는 서비스 (스케줄러 명세서 0.1, 7.2).
 */
@Service
@RequiredArgsConstructor
public class NotificationRetentionService {

    public static final long NOTIFICATION_RETENTION_DAYS = 90L;
    public static final long REMINDER_RETENTION_DAYS = 7L;
    public static final long SENTINEL_RETENTION_DAYS = 14L;
    public static final int BATCH_SIZE = 1000;
    public static final int SINGLE_BATCH_TIMEOUT_SECONDS = 10;

    private final NotificationRepository notificationRepository;
    private final NotificationReminderRepository reminderRepository;
    private final Clock clock;

    /**
     * 생성된 지 90일이 지난 알림을 최대 {@code batchSize}건만큼 단일 배치 트랜잭션으로 물리 삭제한다 (스케줄러 명세서 7.2).
     *
     * @param batchSize 1회 삭제 상한 건수
     * @return 삭제된 알림 행 수
     */
    @Transactional(timeout = SINGLE_BATCH_TIMEOUT_SECONDS)
    public int deleteExpiredNotificationsBatch(int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(NOTIFICATION_RETENTION_DAYS);
        return notificationRepository.deleteExpiredNotifications(cutoff, batchSize);
    }

    /**
     * 종결 상태({@code SENT}, {@code CANCELED})가 된 지 7일이 지난 실제 참가자 예약({@code participant_id > 0})을
     * 최대 {@code batchSize}건만큼 단일 배치 트랜잭션으로 물리 삭제한다 (스케줄러 명세서 7.2).
     *
     * @param batchSize 1회 삭제 상한 건수
     * @return 삭제된 예약 행 수
     */
    @Transactional(timeout = SINGLE_BATCH_TIMEOUT_SECONDS)
    public int deleteExpiredRemindersBatch(int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(REMINDER_RETENTION_DAYS);
        return reminderRepository.deleteExpiredCompletedReminders(cutoff, batchSize);
    }

    /**
     * SQS 최대 보관 기간(14일)이 경과한 시험·탈퇴 음수 표식 행({@code participant_id < 0})을
     * 최대 {@code batchSize}건만큼 단일 배치 트랜잭션으로 물리 삭제한다 (스케줄러 명세서 0.2.3, 7.2).
     *
     * @param batchSize 1회 삭제 상한 건수
     * @return 삭제된 음수 표식 행 수
     */
    @Transactional(timeout = SINGLE_BATCH_TIMEOUT_SECONDS)
    public int deleteExpiredSentinelRemindersBatch(int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(SENTINEL_RETENTION_DAYS);
        return reminderRepository.deleteExpiredSentinelReminders(cutoff, batchSize);
    }
}

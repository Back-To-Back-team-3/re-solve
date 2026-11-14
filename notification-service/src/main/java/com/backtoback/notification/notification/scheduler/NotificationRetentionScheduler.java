package com.backtoback.notification.notification.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.notification.notification.service.NotificationRetentionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 03:30(Asia/Seoul)에 보관 기간이 지난 알림(90일 경과)과 종결된 시험 시작 예약(7일 경과)·음수 표식(14일 경과)을
 * 1,000건 배치 단위로 번갈아 반복 정리하는 스케줄러 (스케줄러 명세서 0.1, 7.2).
 * 한쪽 대량 적체가 다른 쪽 정리를 기아(starvation) 상태로 만들지 않도록 라운드로빈 배치 방식으로 실행하며,
 * ShedLock {@code lockAtMostFor = 30분}의 절반 이하인 10분을 회차 목표 실행 시간 상한으로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetentionScheduler {

    public static final int BATCH_SIZE = NotificationRetentionService.BATCH_SIZE;
    public static final Duration MAX_EXECUTION_DURATION = Duration.ofMinutes(10);
    public static final Duration SINGLE_BATCH_TIMEOUT
        = Duration.ofSeconds(NotificationRetentionService.SINGLE_BATCH_TIMEOUT_SECONDS);
    public static final Duration SELECTION_WINDOW = MAX_EXECUTION_DURATION.minus(SINGLE_BATCH_TIMEOUT);

    private final NotificationRetentionService retentionService;
    private final Clock clock;

    /**
     * 매일 03:30에 만료 알림(90일 경과), 종결 예약(7일 경과), 만료 표식(14일 경과)을
     * 배치 단위로 번갈아 반복 삭제한다 (스케줄러 명세서 7.2).
     */
    @Scheduled(
        cron = "${notification.retention.cleanup-cron:0 30 3 * * *}",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = "NotificationRetentionScheduler",
        lockAtMostFor = "PT30M"
    )
    public void cleanupExpiredNotificationsAndReminders() {
        Instant selectionDeadline = clock.instant().plus(SELECTION_WINDOW);

        int deletedNotifications = 0;
        int deletedReminders = 0;
        int deletedSentinels = 0;

        boolean hasMoreNotifications = true;
        boolean hasMoreReminders = true;
        boolean hasMoreSentinels = true;

        while (clock.instant().isBefore(selectionDeadline)
            && (hasMoreNotifications || hasMoreReminders || hasMoreSentinels)) {
            if (hasMoreNotifications && clock.instant().isBefore(selectionDeadline)) {
                try {
                    int deleted = retentionService.deleteExpiredNotificationsBatch(BATCH_SIZE);
                    deletedNotifications += deleted;
                    if (deleted < BATCH_SIZE) {
                        hasMoreNotifications = false;
                    }
                } catch (Exception exception) {
                    log.warn("만료 알림(90일 경과) 배치 삭제 중 예외 발생: deletedNotifications={}", deletedNotifications, exception);
                    hasMoreNotifications = false;
                }
            }

            if (hasMoreReminders && clock.instant().isBefore(selectionDeadline)) {
                try {
                    int deleted = retentionService.deleteExpiredRemindersBatch(BATCH_SIZE);
                    deletedReminders += deleted;
                    if (deleted < BATCH_SIZE) {
                        hasMoreReminders = false;
                    }
                } catch (Exception exception) {
                    log.warn("만료 예약 알림(7일 경과) 배치 삭제 중 예외 발생: deletedReminders={}", deletedReminders, exception);
                    hasMoreReminders = false;
                }
            }

            if (hasMoreSentinels && clock.instant().isBefore(selectionDeadline)) {
                try {
                    int deleted = retentionService.deleteExpiredSentinelRemindersBatch(BATCH_SIZE);
                    deletedSentinels += deleted;
                    if (deleted < BATCH_SIZE) {
                        hasMoreSentinels = false;
                    }
                } catch (Exception exception) {
                    log.warn("만료 음수 표식(14일 경과) 배치 삭제 중 예외 발생: deletedSentinels={}", deletedSentinels, exception);
                    hasMoreSentinels = false;
                }
            }
        }

        if (deletedNotifications > 0 || deletedReminders > 0 || deletedSentinels > 0) {
            log
                .info(
                    "알림 보관 정리 배치 완료: deletedNotifications={}, deletedReminders={}, deletedSentinels={}",
                    deletedNotifications,
                    deletedReminders,
                    deletedSentinels
                );
        }
    }
}

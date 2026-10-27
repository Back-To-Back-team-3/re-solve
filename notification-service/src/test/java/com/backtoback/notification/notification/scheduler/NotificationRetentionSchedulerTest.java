package com.backtoback.notification.notification.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.notification.service.NotificationRetentionService;

class NotificationRetentionSchedulerTest {

    private static final Instant INITIAL_INSTANT = Instant.parse("2026-10-27T18:30:00Z");

    private MutableClock clock;
    private NotificationRetentionService retentionService;
    private NotificationRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_INSTANT, ClockConfiguration.SERVICE_ZONE);
        retentionService = mock(NotificationRetentionService.class);
        scheduler = new NotificationRetentionScheduler(retentionService, clock);
    }

    @Test
    @DisplayName("7.2 만료 알림(90일)·종결 예약(7일)·만료 표식(14일)을 배치 단위로 번갈아 호출하여 한쪽 대량 적체로 인한 기아를 방지한다")
    void cleanupExpiredNotificationsAndRemindersInterleavesBatchesUntilUnderBatchSize() {
        // given
        given(retentionService.deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE))
            .willReturn(1000, 350);
        given(retentionService.deleteExpiredRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE))
            .willReturn(1000, 120);
        given(retentionService.deleteExpiredSentinelRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE))
            .willReturn(40);

        // when
        scheduler.cleanupExpiredNotificationsAndReminders();

        // then: 1라운드(알림 -> 예약 -> 표식) 후 2라운드(알림 -> 예약) 순서로 번갈아 실행
        InOrder inOrder = inOrder(retentionService);
        inOrder.verify(retentionService).deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE);
        inOrder.verify(retentionService).deleteExpiredRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
        inOrder.verify(retentionService).deleteExpiredSentinelRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
        inOrder.verify(retentionService).deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE);
        inOrder.verify(retentionService).deleteExpiredRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
        verify(retentionService, times(1))
            .deleteExpiredSentinelRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
    }

    @Test
    @DisplayName("0.1 만료 알림 삭제 중 예외가 발생해도 이미 커밋된 배치는 유지하고 예약·표식 정리 단계를 계속 수행한다")
    void cleanupWhenNotificationBatchFailsContinuesToReminderAndSentinelCleanup() {
        // given
        given(retentionService.deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE))
            .willThrow(new RuntimeException("DB 일시 오류"));
        given(retentionService.deleteExpiredRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE)).willReturn(50);
        given(retentionService.deleteExpiredSentinelRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE))
            .willReturn(5);

        // when
        scheduler.cleanupExpiredNotificationsAndReminders();

        // then
        verify(retentionService).deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE);
        verify(retentionService).deleteExpiredRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
        verify(retentionService).deleteExpiredSentinelRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
    }

    @Test
    @DisplayName("0.1 회차 실행 시간 상한(SELECTION_WINDOW)에 도달하면 추가 배치 삭제를 중단하고 다음 회차로 넘긴다")
    void cleanupStopsWhenSelectionDeadlineReached() {
        // given
        given(retentionService.deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE))
            .willAnswer(invocation -> {
                clock.advanceMillis(NotificationRetentionScheduler.SELECTION_WINDOW.toMillis());
                return 1000;
            });

        // when
        scheduler.cleanupExpiredNotificationsAndReminders();

        // then
        verify(retentionService, times(1)).deleteExpiredNotificationsBatch(NotificationRetentionScheduler.BATCH_SIZE);
        verify(retentionService, times(0)).deleteExpiredRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
        verify(retentionService, times(0))
            .deleteExpiredSentinelRemindersBatch(NotificationRetentionScheduler.BATCH_SIZE);
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> currentInstant;
        private final ZoneId zone;

        private MutableClock(Instant initialInstant, ZoneId zone) {
            this.currentInstant = new AtomicReference<>(initialInstant);
            this.zone = zone;
        }

        private void advanceMillis(long millis) {
            currentInstant.updateAndGet(instant -> instant.plusMillis(millis));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant.get(), zone);
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    }
}

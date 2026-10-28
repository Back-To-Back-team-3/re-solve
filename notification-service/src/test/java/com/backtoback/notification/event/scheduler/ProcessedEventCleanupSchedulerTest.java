package com.backtoback.notification.event.scheduler;

import static org.mockito.BDDMockito.given;
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

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.event.service.ProcessedEventCleanupService;

class ProcessedEventCleanupSchedulerTest {

    private static final Instant INITIAL_INSTANT = Instant.parse("2026-10-27T17:10:00Z");

    private MutableClock clock;
    private ProcessedEventCleanupService cleanupService;
    private ProcessedEventCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_INSTANT, ClockConfiguration.SERVICE_ZONE);
        cleanupService = mock(ProcessedEventCleanupService.class);
        scheduler = new ProcessedEventCleanupScheduler(cleanupService, clock);
    }

    @Test
    @DisplayName("0.2.3 14일 경과한 소비 이벤트 기록을 1,000건 미만 반환될 때까지 반복 삭제한다")
    void cleanupExpiredProcessedEventsLoopsUntilUnderBatchSize() {
        // given
        given(cleanupService.deleteExpiredProcessedEventsBatch(ProcessedEventCleanupScheduler.BATCH_SIZE))
            .willReturn(1000, 240);

        // when
        scheduler.cleanupExpiredProcessedEvents();

        // then
        verify(cleanupService, times(2)).deleteExpiredProcessedEventsBatch(ProcessedEventCleanupScheduler.BATCH_SIZE);
    }

    @Test
    @DisplayName("0.1 회차 실행 시간 상한(SELECTION_WINDOW)에 도달하면 소비 이벤트 배치 삭제를 중단한다")
    void cleanupExpiredProcessedEventsStopsWhenSelectionDeadlineReached() {
        // given
        given(cleanupService.deleteExpiredProcessedEventsBatch(ProcessedEventCleanupScheduler.BATCH_SIZE))
            .willAnswer(invocation -> {
                clock.advanceMillis(ProcessedEventCleanupScheduler.SELECTION_WINDOW.toMillis());
                return 1000;
            });

        // when
        scheduler.cleanupExpiredProcessedEvents();

        // then
        verify(cleanupService, times(1)).deleteExpiredProcessedEventsBatch(ProcessedEventCleanupScheduler.BATCH_SIZE);
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

package com.backtoback.integration.infra.event.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.integration.infra.event.service.EventCleanupService;

@ExtendWith(MockitoExtension.class)
class ProcessedEventCleanupSchedulerTest {

    @Mock
    private EventCleanupService eventCleanupService;

    private Clock fixedClock;
    private ProcessedEventCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-28T02:10:00Z"), ZoneId.of("Asia/Seoul"));
        scheduler = new ProcessedEventCleanupScheduler(eventCleanupService, fixedClock);
    }

    @Test
    @DisplayName("cleanupExpiredProcessedEvents 호출 시 배치 크기 미만이 반환될 때까지 반복 삭제한다")
    void cleanupSuccess() {
        given(eventCleanupService.deleteExpiredProcessedEventsBatch(1000)).willReturn(1000, 50);

        scheduler.cleanupExpiredProcessedEvents();

        verify(eventCleanupService, org.mockito.Mockito.times(2)).deleteExpiredProcessedEventsBatch(1000);
    }
}

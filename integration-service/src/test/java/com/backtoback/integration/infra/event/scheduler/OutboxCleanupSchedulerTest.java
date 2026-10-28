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
class OutboxCleanupSchedulerTest {

    @Mock
    private EventCleanupService eventCleanupService;

    private Clock fixedClock;
    private OutboxCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneId.of("Asia/Seoul"));
        scheduler = new OutboxCleanupScheduler(eventCleanupService, fixedClock);
    }

    @Test
    @DisplayName("cleanupExpiredPublishedEvents 호출 시 배치 크기 미만이 반환될 때까지 반복 삭제한다")
    void cleanupSuccess() {
        // 첫 번째 1,000건 삭제, 두 번째 300건 삭제 후 종료
        given(eventCleanupService.deleteExpiredPublishedEventsBatch(1000)).willReturn(1000, 300);

        scheduler.cleanupExpiredPublishedEvents();

        verify(eventCleanupService, org.mockito.Mockito.times(2)).deleteExpiredPublishedEventsBatch(1000);
    }
}

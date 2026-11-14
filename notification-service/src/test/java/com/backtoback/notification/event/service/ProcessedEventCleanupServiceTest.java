package com.backtoback.notification.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.event.repository.ProcessedEventRepository;

class ProcessedEventCleanupServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-27T17:10:00Z"); // 2026-10-28T02:10:00 KST

    private ProcessedEventRepository processedEventRepository;
    private Clock clock;
    private ProcessedEventCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        processedEventRepository = mock(ProcessedEventRepository.class);
        clock = Clock.fixed(FIXED_INSTANT, ClockConfiguration.SERVICE_ZONE);
        cleanupService = new ProcessedEventCleanupService(processedEventRepository, clock);
    }

    @Test
    @DisplayName("0.2.3 현재 시각 기준 14일 이전 처리된 소비 이벤트 기록을 배치 크기만큼 삭제한다")
    void deleteExpiredProcessedEventsBatchDeletesOlderThan14Days() {
        // given
        LocalDateTime expectedCutoff = LocalDateTime.now(clock).minusDays(14);
        given(processedEventRepository.deleteExpiredProcessedEvents(expectedCutoff, 1000)).willReturn(650);

        // when
        int deleted = cleanupService.deleteExpiredProcessedEventsBatch(1000);

        // then
        assertThat(deleted).isEqualTo(650);
        verify(processedEventRepository).deleteExpiredProcessedEvents(expectedCutoff, 1000);
    }
}

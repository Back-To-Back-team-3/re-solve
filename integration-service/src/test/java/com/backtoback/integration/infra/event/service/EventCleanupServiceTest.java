package com.backtoback.integration.infra.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.integration.infra.event.repository.OutboxEventRepository;
import com.backtoback.integration.infra.event.repository.ProcessedEventRepository;

@ExtendWith(MockitoExtension.class)
class EventCleanupServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private Clock fixedClock;
    private EventCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneId.of("Asia/Seoul"));
        cleanupService = new EventCleanupService(outboxEventRepository, processedEventRepository, fixedClock);
    }

    @Test
    @DisplayName("7일 경과한 PUBLISHED 이벤트가 없으면 0을 반환한다")
    void deleteOutboxWhenEmpty() {
        given(outboxEventRepository.findExpiredPublishedEventIds(any(), eq(1000))).willReturn(Collections.emptyList());

        int deleted = cleanupService.deleteExpiredPublishedEventsBatch(1000);

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("7일 경과한 PUBLISHED 이벤트가 있으면 ID를 조회하여 일괄 삭제한다")
    void deleteOutboxSuccess() {
        given(outboxEventRepository.findExpiredPublishedEventIds(any(), eq(1000))).willReturn(List.of(1L, 2L, 3L));
        given(outboxEventRepository.deleteByIdIn(List.of(1L, 2L, 3L))).willReturn(3);

        int deleted = cleanupService.deleteExpiredPublishedEventsBatch(1000);

        assertThat(deleted).isEqualTo(3);
        verify(outboxEventRepository).deleteByIdIn(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("14일 경과한 소비 이벤트 기록이 없으면 0을 반환한다")
    void deleteProcessedWhenEmpty() {
        given(processedEventRepository.findExpiredProcessedEventIds(any(), eq(1000)))
            .willReturn(Collections.emptyList());

        int deleted = cleanupService.deleteExpiredProcessedEventsBatch(1000);

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("14일 경과한 소비 이벤트 기록이 있으면 ID를 조회하여 일괄 삭제한다")
    void deleteProcessedSuccess() {
        given(processedEventRepository.findExpiredProcessedEventIds(any(), eq(1000))).willReturn(List.of(10L, 20L));
        given(processedEventRepository.deleteByIdIn(List.of(10L, 20L))).willReturn(2);

        int deleted = cleanupService.deleteExpiredProcessedEventsBatch(1000);

        assertThat(deleted).isEqualTo(2);
        verify(processedEventRepository).deleteByIdIn(List.of(10L, 20L));
    }
}

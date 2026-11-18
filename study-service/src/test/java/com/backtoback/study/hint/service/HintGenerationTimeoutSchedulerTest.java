package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.repository.HintRequestRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 힌트 생성 회수 스케줄러 단위 테스트 (scheduler §6.1).
 */
@ExtendWith(MockitoExtension.class)
class HintGenerationTimeoutSchedulerTest {

    private static final Clock FIXED_CLOCK
        = Clock.fixed(Instant.parse("2026-11-18T05:00:00Z"), ClockConfiguration.SERVICE_ZONE);

    @Mock
    private HintRecoveryService hintRecoveryService;

    @Mock
    private HintBlockCheckService hintBlockCheckService;

    @Mock
    private HintRequestRepository hintRequestRepository;

    private SimpleMeterRegistry meterRegistry;
    private HintGenerationTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler
            = new HintGenerationTimeoutScheduler(
                hintRecoveryService,
                hintBlockCheckService,
                hintRequestRepository,
                FIXED_CLOCK,
                meterRegistry
            );
        scheduler.initMetrics();
    }

    @Test
    @DisplayName("진행 중 상태와 1분 전 기준 시각, 최대 100건으로 회수 대상을 조회한다")
    void recoverStalledRequests_queriesInProgressWithOneMinuteCutoffAndLimit() {
        given(hintRequestRepository.findRecoveryTargets(any(), any(), any())).willReturn(List.of());

        scheduler.recoverStalledRequests();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(hintRequestRepository)
            .should()
            .findRecoveryTargets(
                eq(List.of(HintRequestStatus.REQUESTED, HintRequestStatus.GENERATING)),
                eq(LocalDateTime.of(2026, 11, 18, 13, 59)),
                pageable.capture()
            );
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        then(hintRecoveryService).should(never()).recover(any(), any(), anyString());
    }

    @Test
    @DisplayName("1건 회수가 실패해도 나머지 건은 계속 회수하고 결과별 지표를 쌓는다")
    void recoverStalledRequests_oneFails_continuesAndCounts() {
        HintRequest first = mock(HintRequest.class);
        HintRequest second = mock(HintRequest.class);
        HintRequest third = mock(HintRequest.class);
        given(hintRequestRepository.findRecoveryTargets(any(), any(), any())).willReturn(List.of(first, second, third));
        willThrow(new RuntimeException("테스트 예외")).given(hintRecoveryService).recover(eq(first), any(), anyString());
        given(hintRecoveryService.recover(eq(second), any(), anyString()))
            .willReturn(HintRecoveryService.Result.FALLBACK);
        given(hintRecoveryService.recover(eq(third), any(), anyString()))
            .willReturn(HintRecoveryService.Result.BLOCKED);

        scheduler.recoverStalledRequests();

        then(hintRecoveryService).should(times(3)).recover(any(), any(), anyString());
        assertThat(count("hint_recovered_fallback")).isEqualTo(1.0);
        assertThat(count("hint_recovered_blocked")).isEqualTo(1.0);
        assertThat(count("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("건마다 sched- 접두사의 서로 다른 추적 ID로 회수한다")
    void recoverStalledRequests_eachItemHasOwnCorrelationId() {
        HintRequest first = mock(HintRequest.class);
        HintRequest second = mock(HintRequest.class);
        given(hintRequestRepository.findRecoveryTargets(any(), any(), any())).willReturn(List.of(first, second));
        given(hintRecoveryService.recover(any(), any(), anyString())).willReturn(HintRecoveryService.Result.SKIPPED);

        scheduler.recoverStalledRequests();

        ArgumentCaptor<String> correlationIds = ArgumentCaptor.forClass(String.class);
        then(hintRecoveryService).should(times(2)).recover(any(), any(), correlationIds.capture());
        assertThat(correlationIds.getAllValues())
            .allMatch(id -> id.startsWith("sched-HintGenerationTimeoutScheduler-"))
            .doesNotHaveDuplicates();
        assertThat(count("hint_recovered_fallback")).isZero();
    }

    @Test
    @DisplayName("차단 판정 결과를 회수 서비스에 넘기고 차단 판정 실패도 회수 실패로 집계한다")
    void recoverStalledRequests_passesBlockedReasonAndCountsCheckFailure() {
        HintRequest blocked = mock(HintRequest.class);
        HintRequest broken = mock(HintRequest.class);
        given(blocked.getMemberId()).willReturn(10L);
        given(blocked.getProblemId()).willReturn(20L);
        given(broken.getMemberId()).willReturn(11L);
        given(broken.getProblemId()).willReturn(21L);
        given(hintRequestRepository.findRecoveryTargets(any(), any(), any())).willReturn(List.of(blocked, broken));
        given(hintBlockCheckService.findBlockedReason(10L, 20L)).willReturn(Optional.of("EXAM"));
        willThrow(new IllegalStateException("테스트 예외")).given(hintBlockCheckService).findBlockedReason(11L, 21L);
        given(hintRecoveryService.recover(eq(blocked), eq(Optional.of("EXAM")), anyString()))
            .willReturn(HintRecoveryService.Result.BLOCKED);

        scheduler.recoverStalledRequests();

        then(hintRecoveryService).should(never()).recover(eq(broken), any(), anyString());
        assertThat(count("hint_recovered_blocked")).isEqualTo(1.0);
        assertThat(count("failed")).isEqualTo(1.0);
    }

    private double count(String result) {
        return meterRegistry.get("scheduler.items").tag("result", result).counter().count();
    }
}

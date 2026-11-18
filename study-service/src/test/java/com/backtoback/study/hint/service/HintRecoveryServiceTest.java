package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.event.HintReadyPayload;
import com.backtoback.study.hint.repository.HintDailyUsageRepository;
import com.backtoback.study.hint.repository.HintRequestRepository;

/**
 * 멈춘 힌트 요청 회수 단위 테스트 (scheduler §6.1, 정책 §8.1 ⑤, event-contract S8).
 */
@ExtendWith(MockitoExtension.class)
class HintRecoveryServiceTest {

    private static final Clock FIXED_CLOCK
        = Clock.fixed(Instant.parse("2026-11-18T05:00:00Z"), ClockConfiguration.SERVICE_ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 14, 0);
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 17, 23, 58);
    private static final String CORRELATION_ID = "sched-test";

    @Mock
    private HintRequestRepository hintRequestRepository;

    @Mock
    private HintDailyUsageRepository hintDailyUsageRepository;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private HintRecoveryService hintRecoveryService;

    @BeforeEach
    void setUp() {
        hintRecoveryService
            = new HintRecoveryService(FIXED_CLOCK, hintRequestRepository, hintDailyUsageRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("차단이 아니면 FALLBACK으로 회수하고 HintReady(FALLBACK, isCached=false)를 기록한다")
    void recover_notBlocked_fallbackAndPublish() {
        HintRequest target = target(HintRequestStatus.GENERATING);
        given(hintRequestRepository.recoverAsFallback(1L, HintRequestStatus.GENERATING, NOW)).willReturn(1);

        HintRecoveryService.Result result = hintRecoveryService.recover(target, Optional.empty(), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.FALLBACK);
        HintReadyPayload payload = capturePayload();
        assertThat(payload.result()).isEqualTo("FALLBACK");
        assertThat(payload.isCached()).isFalse();
        assertThat(payload.hintRequestId()).isEqualTo("1");
        assertThat(payload.problemRevisionId()).isEqualTo("30");
        assertThat(payload.level()).isEqualTo(2);
        assertThat(payload.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-18T14:00:00+09:00"));
        then(hintDailyUsageRepository).should(never()).decrementIfPositive(anyLong(), any(), any());
    }

    @Test
    @DisplayName("REQUESTED 요청도 조회 시점 상태를 기대값으로 FALLBACK 회수한다")
    void recover_requested_usesRequestedAsExpectedStatus() {
        HintRequest target = target(HintRequestStatus.REQUESTED);
        given(hintRequestRepository.recoverAsFallback(1L, HintRequestStatus.REQUESTED, NOW)).willReturn(1);

        HintRecoveryService.Result result = hintRecoveryService.recover(target, Optional.empty(), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.FALLBACK);
    }

    @Test
    @DisplayName("시험 차단 중이면 BLOCKED(EXAM)로 회수하고 요청일 횟수를 반환한 뒤 HintReady(BLOCKED)를 기록한다")
    void recover_examBlocked_blockRefundAndPublish() {
        HintRequest target = target(HintRequestStatus.GENERATING);
        given(hintRequestRepository.recoverAsBlocked(1L, HintRequestStatus.GENERATING, "EXAM", NOW)).willReturn(1);
        given(hintDailyUsageRepository.decrementIfPositive(10L, LocalDate.of(2026, 11, 17), NOW)).willReturn(1);

        HintRecoveryService.Result result
            = hintRecoveryService
                .recover(target, Optional.of(HintBlockCheckService.BLOCKED_REASON_EXAM), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.BLOCKED);
        then(hintDailyUsageRepository).should().decrementIfPositive(10L, LocalDate.of(2026, 11, 17), NOW);
        assertThat(capturePayload().result()).isEqualTo("BLOCKED");
        then(hintRequestRepository).should(never()).recoverAsFallback(anyLong(), any(), any());
    }

    @Test
    @DisplayName("진단 차단 중이면 BLOCKED(DIAGNOSIS)로 회수한다")
    void recover_diagnosisBlocked_blockWithDiagnosisReason() {
        HintRequest target = target(HintRequestStatus.REQUESTED);
        given(hintRequestRepository.recoverAsBlocked(1L, HintRequestStatus.REQUESTED, "DIAGNOSIS", NOW)).willReturn(1);
        given(hintDailyUsageRepository.decrementIfPositive(anyLong(), any(), any())).willReturn(1);

        HintRecoveryService.Result result
            = hintRecoveryService
                .recover(target, Optional.of(HintBlockCheckService.BLOCKED_REASON_DIAGNOSIS), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.BLOCKED);
        then(hintRequestRepository).should().recoverAsBlocked(1L, HintRequestStatus.REQUESTED, "DIAGNOSIS", NOW);
    }

    @Test
    @DisplayName("반환할 일일 횟수가 없어도 BLOCKED 회수와 HintReady 기록은 유지한다")
    void recover_blockedWithoutUsage_keepsRecovery() {
        HintRequest target = target(HintRequestStatus.GENERATING);
        given(hintRequestRepository.recoverAsBlocked(anyLong(), any(), anyString(), any())).willReturn(1);
        given(hintDailyUsageRepository.decrementIfPositive(anyLong(), any(), any())).willReturn(0);

        HintRecoveryService.Result result
            = hintRecoveryService
                .recover(target, Optional.of(HintBlockCheckService.BLOCKED_REASON_EXAM), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.BLOCKED);
        assertThat(capturePayload().result()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("그사이 다른 경로로 종결돼 갱신 행이 없으면 횟수 반환·이벤트 기록 없이 SKIPPED를 반환한다")
    void recover_alreadyTerminated_skipped() {
        HintRequest target = target(HintRequestStatus.GENERATING);
        given(hintRequestRepository.recoverAsFallback(1L, HintRequestStatus.GENERATING, NOW)).willReturn(0);

        HintRecoveryService.Result result = hintRecoveryService.recover(target, Optional.empty(), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.SKIPPED);
        then(outboxEventWriter).should(never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("차단 회수 경합에서 갱신 행이 없으면 횟수를 반환하지 않는다")
    void recover_blockedButAlreadyTerminated_noRefund() {
        HintRequest target = target(HintRequestStatus.GENERATING);
        given(hintRequestRepository.recoverAsBlocked(anyLong(), any(), anyString(), any())).willReturn(0);

        HintRecoveryService.Result result
            = hintRecoveryService
                .recover(target, Optional.of(HintBlockCheckService.BLOCKED_REASON_EXAM), CORRELATION_ID);

        assertThat(result).isEqualTo(HintRecoveryService.Result.SKIPPED);
        then(hintDailyUsageRepository).should(never()).decrementIfPositive(anyLong(), any(), any());
        then(outboxEventWriter).should(never()).append(any(), any(), any(), any(), any(), any());
    }

    private static HintRequest target(HintRequestStatus status) {
        HintRequest target = mock(HintRequest.class);
        given(target.getId()).willReturn(1L);
        lenient().when(target.getMemberId()).thenReturn(10L);
        lenient().when(target.getProblemId()).thenReturn(20L);
        given(target.getStatus()).willReturn(status);
        lenient().when(target.getProblemRevisionId()).thenReturn(30L);
        lenient().when(target.getLevel()).thenReturn(2);
        lenient().when(target.getCreatedAt()).thenReturn(REQUESTED_AT);
        return target;
    }

    private HintReadyPayload capturePayload() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        then(outboxEventWriter)
            .should()
            .append(eq("HINT_REQUEST"), eq("HintReady"), eq("v1"), eq("1"), eq(CORRELATION_ID), captor.capture());
        return (HintReadyPayload)captor.getValue();
    }
}

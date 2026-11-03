package com.backtoback.study.membership.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.study.membership.repository.StudyMembershipRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 만료 스케줄러의 배치 조회·예외 격리 시나리오를 검증한다(스케줄러 명세서 §5.1).
 */
@ExtendWith(MockitoExtension.class)
class StudyApplicationExpirySchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final Instant NOW_INSTANT = NOW.toInstant(ZoneOffset.of("+09:00"));

    @Mock
    private StudyMembershipExpiryService expiryService;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private Clock clock;

    private StudyApplicationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(NOW_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.of("+09:00"));
        scheduler
            = new StudyApplicationExpiryScheduler(
                expiryService,
                membershipRepository,
                clock,
                new SimpleMeterRegistry()
            );
        scheduler.initMetrics();
    }

    @Test
    @DisplayName("만료 대상이 없으면 expire를 호출하지 않는다")
    void expireBatch_noExpiredIds_neverCallsExpire() {
        given(membershipRepository.findExpiredPendingIds(any(), any(Integer.class))).willReturn(List.of());

        scheduler.expireBatch();

        verify(expiryService, never()).expire(anyLong(), anyString());
    }

    @Test
    @DisplayName("1건 예외 발생 시 나머지 건은 계속 처리된다")
    void expireBatch_oneItemFails_continuesWithOthers() {
        given(membershipRepository.findExpiredPendingIds(any(), any(Integer.class))).willReturn(List.of(1L, 2L, 3L));
        willThrow(new RuntimeException("테스트 예외")).given(expiryService).expire(eq(1L), anyString());

        scheduler.expireBatch();

        verify(expiryService, times(3)).expire(anyLong(), anyString());
    }
}

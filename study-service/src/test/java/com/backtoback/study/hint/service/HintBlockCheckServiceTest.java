package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.domain.HintBlockReplica;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;

/**
 * 회수 직전 차단 사유 판정 단위 테스트 (scheduler §6.1, 정책 §8.1 ⑥ fail-closed).
 */
@ExtendWith(MockitoExtension.class)
class HintBlockCheckServiceTest {

    private static final Clock FIXED_CLOCK
        = Clock.fixed(Instant.parse("2026-11-18T05:00:00Z"), ClockConfiguration.SERVICE_ZONE);

    @Mock
    private HintBlockReplicaRepository hintBlockReplicaRepository;

    private HintBlockCheckService hintBlockCheckService;

    @BeforeEach
    void setUp() {
        hintBlockCheckService = new HintBlockCheckService(FIXED_CLOCK, hintBlockReplicaRepository);
    }

    @Test
    @DisplayName("활성 차단 복제본이 없으면 빈 값을 돌려준다")
    void findBlockedReason_noBlock_empty() {
        given(hintBlockReplicaRepository.findActiveBlocks(eq(10L), eq(20L), any(), any())).willReturn(List.of());

        assertThat(hintBlockCheckService.findBlockedReason(10L, 20L)).isEmpty();
    }

    @Test
    @DisplayName("시험·대회 차단 복제본이 있으면 EXAM을 돌려준다")
    void findBlockedReason_contestBlock_exam() {
        HintBlockReplica block = replica(HintBlockContextType.CONTEST);
        given(hintBlockReplicaRepository.findActiveBlocks(eq(10L), eq(20L), any(), any())).willReturn(List.of(block));

        assertThat(hintBlockCheckService.findBlockedReason(10L, 20L)).contains("EXAM");
    }

    @Test
    @DisplayName("진단 차단 복제본이 있으면 DIAGNOSIS를 돌려준다")
    void findBlockedReason_diagnosisBlock_diagnosis() {
        HintBlockReplica block = replica(HintBlockContextType.DIAGNOSIS);
        given(hintBlockReplicaRepository.findActiveBlocks(eq(10L), eq(20L), any(), any())).willReturn(List.of(block));

        assertThat(hintBlockCheckService.findBlockedReason(10L, 20L)).contains("DIAGNOSIS");
    }

    @Test
    @DisplayName("차단 복제본 조회가 실패하면 fail-closed로 EXAM을 돌려준다")
    void findBlockedReason_lookupFails_examFailClosed() {
        given(hintBlockReplicaRepository.findActiveBlocks(eq(10L), eq(20L), any(), any()))
            .willThrow(new QueryTimeoutException("timeout"));

        assertThat(hintBlockCheckService.findBlockedReason(10L, 20L)).contains("EXAM");
    }

    private static HintBlockReplica replica(HintBlockContextType contextType) {
        HintBlockReplica replica = mock(HintBlockReplica.class);
        given(replica.getContextType()).willReturn(contextType);
        return replica;
    }
}

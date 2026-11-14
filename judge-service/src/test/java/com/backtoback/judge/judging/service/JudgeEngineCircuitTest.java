package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

/** 한 인스턴스의 서킷 차단·단일 복구 확인·늦은 응답 격리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class JudgeEngineCircuitTest {
    private static final Instant START = Instant.parse("2026-10-31T00:00:00Z");
    @Mock
    private Clock clock;
    private JudgeEngineCircuit circuit;

    /** 테스트마다 독립 서킷을 준비한다. */
    @BeforeEach
    void prepare() {
        circuit = new JudgeEngineCircuit(clock);
    }

    /** 30초 경계와 복구 확인의 단일 허가를 검증한다. */
    @Test
    @DisplayName("연속 5회 장애 뒤 30초간 선점을 막고 복구 확인 한 건의 실제 응답으로 재개한다")
    void opensAndProbesOnce() {
        open();
        assertThat(circuit.acquire()).isEmpty();
        given(clock.instant()).willReturn(START.plusSeconds(29));
        assertThat(circuit.acquire()).isEmpty();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        try (var probe = circuit.acquire().orElseThrow()) {
            assertThat(circuit.state()).isEqualTo(2);
            assertThat(circuit.acquire()).isEmpty();
            assertThat(probe.invoke(() -> "WA")).contains("WA");
        }
        assertThat(circuit.state()).isZero();
        assertThat(circuit.acquire()).isPresent();
    }

    /** 빈 대기열은 실제 응답이 아니므로 복구 성공으로 취급하지 않는다. */
    @Test
    @DisplayName("복구 후보가 없으면 허가를 반환하고 성공으로 간주하지 않는다")
    void returnsUnusedProbe() {
        open();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        try (var probe = circuit.acquire().orElseThrow()) {
            assertThat(probe.hasCalled()).isFalse();
        }
        assertThat(circuit.state()).isEqualTo(1);
        assertThat(circuit.acquire()).isPresent();
    }

    /** 정상 응답이 연속 실패를 끊고 4xx를 통신 장애로 집계하지 않는지 확인한다. */
    @Test
    @DisplayName("정상 응답은 연속 실패를 초기화하고 사용자 오류 4xx는 서킷을 열지 않는다")
    void countsOnlyConsecutiveEngineFailures() {
        try (var permit = circuit.acquire().orElseThrow()) {
            for (int index = 0; index < 4; index++) {
                fail(permit);
            }
            permit.invoke(() -> "CE");
            for (int index = 0; index < 4; index++) {
                fail(permit);
            }
        }
        assertThat(circuit.state()).isZero();
        var other = new JudgeEngineCircuit(clock);
        try (var permit = other.acquire().orElseThrow()) {
            for (int index = 0; index < 6; index++) {
                assertThatThrownBy(() -> permit.invoke(() -> {
                    throw new JudgeException(JudgeErrorCode.JUDGE0_RESPONSE_FAILED, 400);
                })).isInstanceOf(JudgeException.class);
            }
        }
        assertThat(other.state()).isZero();
    }

    /** 이전 허가의 늦은 응답으로 새 차단 상태를 닫지 않는다. */
    @Test
    @DisplayName("차단 이전 작업의 늦은 정상 응답은 현재 서킷을 닫지 않는다")
    void ignoresOldGenerationSuccess() {
        try (var old = circuit.acquire().orElseThrow()) {
            old.invoke(() -> "AC");
            open();
            old.invoke(() -> "AC");
            assertThat(circuit.state()).isEqualTo(1);
        }
    }

    /** 복구 확인도 통신에 실패하면 그 시각부터 다시 30초를 차단한다. */
    @Test
    @DisplayName("복구 확인이 실패하면 다시 30초를 기다린 뒤 한 건만 허용한다")
    void reopensOnProbeFailure() {
        open();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        try (var probe = circuit.acquire().orElseThrow()) {
            fail(probe);
        }
        assertThat(circuit.acquire()).isEmpty();
        given(clock.instant()).willReturn(START.plusSeconds(59));
        assertThat(circuit.acquire()).isEmpty();
        given(clock.instant()).willReturn(START.plusSeconds(60));
        assertThat(circuit.acquire()).isPresent();
        assertThat(new JudgeEngineCircuit(clock).acquire()).isPresent();
    }

    /** 복구 확인의 첫 응답 이후 호출도 현재 세대의 연속 장애로 집계한다. */
    @Test
    @DisplayName("복구 확인 생성 성공 뒤 같은 작업의 결과 조회가 5회 실패하면 다시 차단한다")
    void countsCallsAfterProbeSuccess() {
        open();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        try (var probe = circuit.acquire().orElseThrow()) {
            probe.invoke(() -> "created-token");
            assertThat(circuit.state()).isZero();
            for (int index = 0; index < 5; index++) {
                fail(probe);
            }
        }
        assertThat(circuit.state()).isEqualTo(1);
        assertThat(circuit.acquire()).isEmpty();
    }

    /** 연속 실패를 채워 서킷을 연다. */
    private void open() {
        given(clock.instant()).willReturn(START);
        try (var permit = circuit.acquire().orElseThrow()) {
            for (int index = 0; index < 5; index++) {
                fail(permit);
            }
        }
        assertThat(circuit.state()).isEqualTo(1);
    }

    /**
     * 실제 Judge0 통신 실패를 서킷에 전달한다.
     *
     * @param permit 현재 호출 허가
     */
    private void fail(JudgeEngineCircuit.Permit permit) {
        assertThatThrownBy(() -> permit.invoke(() -> {
            throw new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED);
        })).isInstanceOf(JudgeException.class);
    }
}

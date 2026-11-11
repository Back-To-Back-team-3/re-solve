package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

/** 인스턴스 안의 제출 워커가 공유하는 Judge0 선점 허가와 30초 복구 확인 상태. */
@Component
@RequiredArgsConstructor
public class JudgeEngineCircuit implements MeterBinder {
    private final Clock clock;
    private int failures;
    private long generation;
    private Instant retryAt;
    private Permit probe;

    /**
     * 닫힌 서킷의 일반 허가 또는 차단 30초 뒤 복구 확인 허가 하나를 제공한다.
     *
     * @return 신규 작업을 선점할 수 있는 허가 또는 빈 값
     */
    public synchronized Optional<Permit> acquire() {
        if (retryAt == null) {
            return Optional.of(new Permit(generation));
        }
        if (clock.instant().isBefore(retryAt) || probe != null) {
            return Optional.empty();
        }
        probe = new Permit(generation);
        return Optional.of(probe);
    }

    /** {@inheritDoc} */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("judge.engine.circuit.state", this, JudgeEngineCircuit::state).register(registry);
    }

    /**
     * 관측용 서킷 상태를 제공한다.
     *
     * @return CLOSED는 0, OPEN은 1, HALF_OPEN은 2
     */
    public synchronized int state() {
        return retryAt == null ? 0 : probe == null ? 1 : 2;
    }

    /**
     * 이미 실행 중인 호출은 유지하고 미호출 선점은 현재 서킷을 재확인한다.
     *
     * @param permit 작업 선점 허가
     * @return 호출을 시작하는 세대, 차단됐으면 null
     */
    private synchronized Long enter(Permit permit) {
        if (permit.called || permit == probe || (retryAt == null && permit.generation == generation)) {
            permit.called = true;
            return generation;
        }
        return null;
    }

    /**
     * 현재 세대의 정상 응답으로 연속 실패를 초기화하거나 복구 확인을 완료한다.
     *
     * @param permit 실제 호출을 수행한 허가
     * @param callGeneration 호출 시작 시각의 서킷 세대
     */
    private synchronized void success(Permit permit, long callGeneration) {
        if (callGeneration != generation) {
            return;
        }
        if (permit == probe) {
            probe = null;
            retryAt = null;
            failures = 0;
            generation++;
        } else if (retryAt == null && callGeneration == generation) {
            failures = 0;
        }
    }

    /**
     * 통신·타임아웃·5xx만 연속 실패로 집계한다. 사용자 코드 판정은 정상 응답이다.
     *
     * @param permit 실패한 호출의 허가
     * @param failure Judge0 클라이언트 오류
     * @param callGeneration 호출 시작 시각의 서킷 세대
     */
    private synchronized void failure(Permit permit, JudgeException failure, long callGeneration) {
        if (callGeneration != generation) {
            return;
        }
        boolean unavailable
            = failure.getErrorCode() == JudgeErrorCode.JUDGE0_CONNECTION_FAILED || (failure.getUpstreamStatus() != null
                && (failure.getUpstreamStatus() == 408 || failure.getUpstreamStatus() >= 500));
        if (!unavailable) {
            return;
        }
        if (permit == probe || (retryAt == null && callGeneration == generation && ++failures >= 5)) {
            retryAt = clock.instant().plusSeconds(30);
            probe = null;
            generation++;
        }
    }

    /** 선점 전 허가와 실제 Judge0 호출·결과를 같은 작업 범위에서 연결한다. */
    public final class Permit implements AutoCloseable {
        private final long generation;
        private boolean called;

        /**
         * 허가를 발급한 서킷 세대를 보존한다.
         *
         * @param generation 서킷 상태 세대
         */
        private Permit(long generation) {
            this.generation = generation;
        }

        /**
         * 허가를 재확인한 뒤 실제 HTTP 호출을 수행하고 응답을 서킷에 반영한다.
         *
         * @param <T> Judge0 응답 타입
         * @param action 실행 생성 또는 기존 토큰 조회
         * @return 응답 또는 호출 전 차단·소유권 상실에 따른 빈 값
         * @throws JudgeException 실제 Judge0 호출이 통신·응답 오류로 실패한 경우
         */
        public <T> Optional<T> invoke(Supplier<T> action) {
            Long callGeneration = enter(this);
            if (callGeneration == null) {
                return Optional.empty();
            }
            try {
                T response = action.get();
                if (response != null) {
                    success(this, callGeneration);
                }
                return Optional.ofNullable(response);
            } catch (JudgeException failure) {
                failure(this, failure, callGeneration);
                throw failure;
            }
        }

        /**
         * 서킷 차단으로 실제 호출을 시작하지 못했는지 확인한다.
         *
         * @return 실제 호출 경로에 진입했는지 여부
         */
        public boolean hasCalled() {
            return called;
        }

        /** 후보가 없거나 실제 호출 전에 중단된 복구 확인 허가를 반환한다. */
        @Override
        public void close() {
            synchronized (JudgeEngineCircuit.this) {
                if (probe == this) {
                    probe = null;
                }
            }
        }
    }
}

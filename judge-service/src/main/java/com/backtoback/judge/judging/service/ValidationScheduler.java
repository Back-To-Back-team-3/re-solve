package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Scheduled;

import com.backtoback.judge.judging.store.ValidationExecutionStore;

import lombok.extern.slf4j.Slf4j;

/** 활성화 설정으로 등록할 때만 검증 실행과 기한 초과 정리를 예약한다. DB 잠금으로 인스턴스 간 선점을 분리한다. */
@Slf4j
public class ValidationScheduler {
    private final ValidationWorker worker;
    private final ValidationExecutionStore store;
    private final Executor executor;
    private final Clock clock;
    private final List<Lane> lanes = List.of(new Lane(), new Lane());

    /**
     * 주기 제어 스레드와 실제 실행 스레드를 분리한다.
     *
     * @param worker 작업 한 건의 상위 실행기
     * @param store 기한 초과 종결 저장소
     * @param executor 실행 전용 스레드 풀
     * @param clock 공통 시각
     */
    public ValidationScheduler(
        ValidationWorker worker,
        ValidationExecutionStore store,
        Executor executor,
        Clock clock
    ) {
        this.worker = worker;
        this.store = store;
        this.executor = executor;
        this.clock = clock;
    }

    /** 대기열이 비면 실행 슬롯별로 1·2·5초 간격을 적용하고, 진행 중 작업보다 먼저 추가 선점하지 않는다. */
    @Scheduled(
        fixedDelay = 1000,
        scheduler = "judgeValidationScheduler"
    )
    public void dispatch() {
        for (var lane : lanes) {
            if (clock.instant().isBefore(lane.nextPoll) || !lane.running.compareAndSet(false, true)) {
                continue;
            }
            try {
                executor.execute(() -> run(lane));
            } catch (RuntimeException rejected) {
                lane.running.set(false);
                log.warn("검증 실행 제출 실패: type={}", rejected.getClass().getSimpleName());
            }
        }
    }

    /** 실행 스레드 점유나 Judge0 장애와 독립적으로 기한이 지난 작업을 건별 트랜잭션으로 종결한다. */
    @Scheduled(
        fixedDelay = 30000,
        scheduler = "judgeValidationScheduler"
    )
    public void expire() {
        try {
            // 한 회차가 다른 예약 작업을 무한히 지연시키지 않도록 처리량을 제한한다.
            for (int count = 0; count < 100 && store.expireOne(); count++) {
                // 각 저장소 호출이 독립적으로 커밋된다.
            }
        } catch (RuntimeException failure) {
            log.warn("검증 기한 초과 종결 실패: type={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 실행 한 건의 실패를 다른 슬롯과 격리하고 다음 조회 시각을 정한다.
     *
     * @param lane 해당 작업만 사용하는 실행 슬롯
     */
    private void run(Lane lane) {
        boolean processed = false;
        try {
            processed = worker.runOnce();
        } catch (RuntimeException failure) {
            log.warn("검증 실행 회차 실패: type={}", failure.getClass().getSimpleName());
        } finally {
            if (processed) {
                lane.emptyRounds = 0;
                lane.nextPoll = clock.instant();
            } else {
                lane.emptyRounds = Math.min(lane.emptyRounds + 1, 3);
                int delay = lane.emptyRounds == 1 ? 1 : lane.emptyRounds == 2 ? 2 : 5;
                lane.nextPoll = clock.instant().plusSeconds(delay);
            }
            lane.running.set(false);
        }
    }

    /** 같은 슬롯의 중복 실행을 막고 빈 대기열 조회 간격을 유지한다. */
    private static final class Lane {
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile Instant nextPoll = Instant.MIN;
        private int emptyRounds;
    }
}

package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/** 활성화 설정으로 등록할 때만 채점 실행과 기한 초과 종결과 임대 회수를 예약한다. DB 잠금으로 인스턴스 간 선점을 분리한다. */
@Slf4j
public class JudgeWorkScheduler {
    // 제출 회수의 25초 ShedLock이 정상 실행 예산의 두 배 이상이어야 한다. (스케줄러 §0.1)
    private static final long RECOVERY_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(12);

    private final BooleanSupplier worker;
    private final BooleanSupplier recovery;
    private final Executor executor;
    private final Clock clock;
    private final JpaTransactionManager transactionManager;
    private final LongSupplier nanoTime;
    private final List<Lane> lanes = List.of(new Lane(), new Lane());

    /**
     * 주기 제어 스레드와 실제 실행 스레드를 분리한다.
     *
     * @param worker 작업 한 건의 상위 실행기
     * @param recovery 기한 초과 종결·임대 회수 저장소
     * @param executor 실행 전용 스레드 풀
     * @param clock 공통 시각
     * @param transactionManager 회수 한 건마다 트랜잭션을 시작할 관리자
     */
    public JudgeWorkScheduler(
        BooleanSupplier worker,
        BooleanSupplier recovery,
        Executor executor,
        Clock clock,
        JpaTransactionManager transactionManager
    ) {
        this(worker, recovery, executor, clock, transactionManager, System::nanoTime);
    }

    /**
     * 회수 실행 예산을 서비스 시각의 변경과 무관하게 측정한다.
     *
     * @param worker 작업 한 건의 상위 실행기
     * @param recovery 기한 초과 종결·임대 회수 저장소
     * @param executor 실행 전용 스레드 풀
     * @param clock 공통 시각
     * @param transactionManager 회수 한 건마다 트랜잭션을 시작할 관리자
     * @param nanoTime 회수 경과 시간을 측정할 단조 시간 공급자
     */
    JudgeWorkScheduler(
        BooleanSupplier worker,
        BooleanSupplier recovery,
        Executor executor,
        Clock clock,
        JpaTransactionManager transactionManager,
        LongSupplier nanoTime
    ) {
        this.worker = worker;
        this.recovery = recovery;
        this.executor = executor;
        this.clock = clock;
        this.transactionManager = transactionManager;
        this.nanoTime = nanoTime;
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
                log.warn("채점 실행 제출 실패: type={}", rejected.getClass().getSimpleName());
            }
        }
    }

    /** 실행 스레드 점유나 Judge0 장애와 독립적으로 기한 초과·임대 만료 작업을 건별 트랜잭션으로 회수한다. */
    @Scheduled(
        fixedDelay = 30000,
        scheduler = "judgeValidationScheduler"
    )
    public void expire() {
        recoverBatch(recovery);
    }

    /**
     * 회차당 최대 100건을 처리하고 독립 트랜잭션마다 남은 실행 예산을 적용한다. (스케줄러 §0.1)
     *
     * @param recoverOne 회수 대상 한 건을 처리하는 저장소 호출
     */
    protected void recoverBatch(BooleanSupplier recoverOne) {
        long deadline = nanoTime.getAsLong() + RECOVERY_BUDGET_NANOS;
        try {
            var dataSource = transactionManager.getDataSource();
            Assert.state(dataSource instanceof HikariDataSource, "채점 회수에는 Hikari 연결 풀이 필요합니다.");
            var pool = (HikariDataSource)dataSource;
            for (int count = 0; count < 100; count++) {
                long remaining = deadline - nanoTime.getAsLong();
                long acquisitionMillis = pool.getConnectionTimeout() + pool.getValidationTimeout();
                // Hikari는 풀 대기 후에 별도 제한시간으로 연결을 검사한다.
                if (remaining < TimeUnit.MILLISECONDS.toNanos(acquisitionMillis) + TimeUnit.SECONDS.toNanos(1)) {
                    return;
                }
                int timeoutSeconds = (int)TimeUnit.NANOSECONDS.toSeconds(remaining);
                var transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.setTimeout(timeoutSeconds);
                var recovered = transaction.execute(status -> recoverWithinBudget(recoverOne, deadline, pool));
                if (!Boolean.TRUE.equals(recovered)) {
                    return;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("채점 작업 회수 실패: type={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * DB 연결 확보 후 남은 예산을 같은 트랜잭션의 JDBC 제한시간에 반영한다.
     *
     * @param recoverOne 회수 대상 한 건을 처리하는 저장소 호출
     * @param deadline 회수 회차의 단조 시각 기준 마감
     * @param pool 회수 트랜잭션이 사용하는 연결 풀
     * @return 처리할 작업이 있었는지 여부
     * @throws IllegalStateException JPA 트랜잭션에 JDBC 연결이 바인딩되지 않은 경우
     */
    private boolean recoverWithinBudget(BooleanSupplier recoverOne, long deadline, HikariDataSource pool) {
        long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(deadline - nanoTime.getAsLong());
        if (remainingSeconds < 1) {
            return false;
        }
        var connection = (ConnectionHolder)TransactionSynchronizationManager.getResource(pool);
        Assert.state(connection != null, "회수 트랜잭션의 JDBC 연결이 필요합니다.");
        connection
            .setTimeoutInMillis(
                Math.min(TimeUnit.SECONDS.toMillis(remainingSeconds), connection.getTimeToLiveInMillis())
            );
        return recoverOne.getAsBoolean();
    }

    /**
     * 실행 한 건의 실패를 다른 슬롯과 격리하고 다음 조회 시각을 정한다.
     *
     * @param lane 해당 작업만 사용하는 실행 슬롯
     */
    private void run(Lane lane) {
        boolean processed = false;
        try {
            processed = worker.getAsBoolean();
        } catch (RuntimeException failure) {
            log.warn("채점 실행 회차 실패: type={}", failure.getClass().getSimpleName());
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

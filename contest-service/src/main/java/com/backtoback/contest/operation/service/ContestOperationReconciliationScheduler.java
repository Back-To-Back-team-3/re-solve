package com.backtoback.contest.operation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.common.scheduling.config.SchedulerLockConfiguration;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.admin.service.ExamRetryService;
import com.backtoback.contest.admin.service.ExamRetryStore;
import com.backtoback.contest.exam.store.ExamSchedulerCursorStore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 미확인 시험 재처리 작업을 같은 식별자로 다시 확인한다 (스케줄러 §4.7). */
@Component
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableScheduling
@Import(SchedulerLockConfiguration.class)
public class ContestOperationReconciliationScheduler {
    private static final String JOB = "ContestOperationReconciliationScheduler";
    private static final Logger log = LoggerFactory.getLogger(ContestOperationReconciliationScheduler.class);
    private final JdbcTemplate jdbc;
    private final ExamRetryStore store;
    private final ExamRetryService retry;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ExamSchedulerCursorStore cursors;
    private final AtomicLong oldest = new AtomicLong();

    /** 미확인 작업의 지표와 대사 의존성을 등록한다. */
    public ContestOperationReconciliationScheduler(
        JdbcTemplate jdbc,
        ExamRetryStore store,
        ExamRetryService retry,
        Clock clock,
        MeterRegistry metrics,
        ExamSchedulerCursorStore cursors
    ) {
        this.jdbc = jdbc;
        this.store = store;
        this.retry = retry;
        this.clock = clock;
        this.metrics = metrics;
        this.cursors = cursors;
        Gauge.builder("scheduler.backlog.oldest.seconds", oldest, AtomicLong::get).tag("job", JOB).register(metrics);
    }

    /** 각 원격 호출은 DB 잠금 없이 실행하고 상태를 다시 확인한다. */
    @Scheduled(
        fixedDelay = 300000,
        initialDelay = 300000
    )
    @SchedulerLock(
        name = "ContestOperationReconciliationScheduler",
        lockAtMostFor = "PT30S"
    )
    public void run() {
        var sample = Timer.start(metrics);
        try {
            var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
            var cutoff = now.minusMinutes(1);
            var oldestTime
                = jdbc
                    .query(
                        "SELECT MIN(created_at) FROM contest_admin_operations "
                            + "WHERE context='EXAM' AND operation_type='EXAM_RETRY' AND state='PENDING'",
                        rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null
                    );
            oldest.set(oldestTime == null ? 0 : Math.max(0, Duration.between(oldestTime, now).getSeconds()));
            if (oldest.get() >= Duration.ofMinutes(10).getSeconds()) {
                log.warn("시험 재처리 미확인 작업 장기 보류: oldestSeconds={}", oldest.get());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var cursor = cursors.load(JOB);
            while (System.nanoTime() < deadline) {
                var candidates = candidates(cutoff, cursor);
                if (candidates.isEmpty()) {
                    cursors.clear(JOB);
                    return;
                }
                for (var candidate : candidates) {
                    if (System.nanoTime() >= deadline) {
                        return;
                    }
                    cursor = new ExamSchedulerCursorStore.Cursor(candidate.updatedAt(), candidate.id());
                    cursors.save(JOB, cursor);
                    metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
                    try {
                        var operation = store.require(candidate.operationId());
                        if (!"PENDING".equals(operation.state()) || operation.updatedAt().isAfter(cutoff)) {
                            metrics.counter("scheduler.items", "job", JOB, "result", "skipped").increment();
                            continue;
                        }
                        var result = retry.reconcile(operation);
                        String state = "PENDING".equals(result.state()) ? "pending" : "success";
                        metrics.counter("scheduler.items", "job", JOB, "result", state).increment();
                    } catch (RuntimeException failure) {
                        metrics.counter("scheduler.items", "job", JOB, "result", "failure").increment();
                        log
                            .error(
                                "관리자 작업 대사 실패: operationId={}, exceptionType={}",
                                candidate.operationId(),
                                failure.getClass().getSimpleName()
                            );
                    }
                }
            }
        } finally {
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    private java.util.List<Candidate> candidates(LocalDateTime cutoff, ExamSchedulerCursorStore.Cursor cursor) {
        String query
            = "SELECT id,operation_id,updated_at FROM contest_admin_operations "
                + "WHERE context='EXAM' AND operation_type='EXAM_RETRY' AND state='PENDING' AND updated_at<=? ";
        org.springframework.jdbc.core.RowMapper<Candidate> mapper
            = (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class));
        if (cursor == null) {
            return jdbc.query(query + "ORDER BY updated_at,id LIMIT 100", mapper, cutoff);
        }
        return jdbc
            .query(
                query + "AND (updated_at>? OR (updated_at=? AND id>?)) " + "ORDER BY updated_at,id LIMIT 100",
                mapper,
                cutoff,
                cursor.time(),
                cursor.time(),
                cursor.id()
            );
    }

    private record Candidate(long id, String operationId, LocalDateTime updatedAt) {
    }
}

package com.backtoback.contest.operation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;

import com.backtoback.common.scheduling.config.SchedulerLockConfiguration;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.admin.service.ExamRetryService;
import com.backtoback.contest.admin.service.ExamRetryStore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

/** 미확인 시험 재처리와 자원 준비 작업을 원래 식별자로 대사한다 (스케줄러 §4.7). */
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
    private final MemberGuardReconciliation memberGuards;
    private final ResourceGuardReconciliation resourceGuards;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ScheduledExecutorService lockRenewals = Executors.newSingleThreadScheduledExecutor();
    private final LockProvider reconciliationLocks;
    private final AtomicLong oldest = new AtomicLong();
    private boolean memberFirst;

    /** 미확인 작업의 지표와 대사 의존성을 등록한다. */
    public ContestOperationReconciliationScheduler(
        JdbcTemplate jdbc,
        ExamRetryStore store,
        ExamRetryService retry,
        MemberGuardReconciliation memberGuards,
        ResourceGuardReconciliation resourceGuards,
        Clock clock,
        MeterRegistry metrics,
        LockProvider lockProvider
    ) {
        this.jdbc = jdbc;
        this.store = store;
        this.retry = retry;
        this.memberGuards = memberGuards;
        this.resourceGuards = resourceGuards;
        this.clock = clock;
        this.metrics = metrics;
        if (!(lockProvider instanceof ExtensibleLockProvider extensible)) {
            throw new IllegalArgumentException("대사 배치에는 잠금 연장을 지원하는 공급자가 필요합니다.");
        }
        this.reconciliationLocks = new KeepAliveLockProvider(extensible, lockRenewals);
        Gauge.builder("scheduler.backlog.oldest.seconds", oldest, AtomicLong::get).tag("job", JOB).register(metrics);
    }

    /** 각 원격 호출은 DB 잠금 없이 실행하고 상태를 다시 확인한다. */
    @Scheduled(
        fixedDelay = 300000,
        initialDelay = 300000
    )
    public void run() {
        var lock
            = reconciliationLocks
                .lock(new LockConfiguration(clock.instant(), JOB, Duration.ofSeconds(30), Duration.ZERO));
        if (lock.isEmpty()) {
            return;
        }
        var sample = Timer.start(metrics);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
            var cutoff = now.minusMinutes(1);
            var oldestTime
                = jdbc
                    .query(
                        "SELECT MIN(created_at) FROM contest_admin_operations "
                            + "WHERE context='EXAM' AND operation_type='EXAM_RETRY' AND state='PENDING'",
                        rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null
                    );
            var guardOldest
                = jdbc
                    .query(
                        "SELECT MIN(prepared_at) FROM contest_resource_guards "
                            + "WHERE state='PREPARED' AND operation_result='PENDING'",
                        rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null
                    );
            if (guardOldest != null && (oldestTime == null || guardOldest.isBefore(oldestTime))) {
                oldestTime = guardOldest;
            }
            oldest.set(oldestTime == null ? 0 : Math.max(0, Duration.between(oldestTime, now).getSeconds()));
            if (oldest.get() >= Duration.ofMinutes(10).getSeconds()) {
                log.warn("미확인 작업 장기 보류: oldestSeconds={}", oldest.get());
            }
            boolean first = memberFirst;
            memberFirst = !memberFirst;
            if (first) {
                reconcileGuards(cutoff, deadline);
            }
            reconcileRetries(cutoff, deadline);
            if (!first) {
                reconcileGuards(cutoff, deadline);
            }
        } finally {
            lock.get().unlock();
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    @PreDestroy
    void stopRenewals() {
        lockRenewals.shutdown();
    }

    private void reconcileRetries(LocalDateTime cutoff, long deadline) {
        // 회차 내에서만 지나간 후보를 건너뛰어 확인 시각 저장에 실패해도 다음 회차에 다시 선택한다.
        Candidate cursor = null;
        while (System.nanoTime() < deadline) {
            var candidates = candidates(cutoff, cursor);
            if (candidates.isEmpty()) {
                return;
            }
            for (var candidate : candidates) {
                if (System.nanoTime() >= deadline) {
                    return;
                }
                cursor = candidate;
                try {
                    if (!markRetryAttempt(candidate, cutoff)) {
                        metrics.counter("scheduler.items", "job", JOB, "result", "skipped").increment();
                        continue;
                    }
                    metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
                    var result = retry.reconcile(store.require(candidate.operationId()));
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
    }

    /** 시도 시각 저장이 실패한 가드도 이번 회차에서 한 번만 선택한다. */
    private void reconcileGuards(LocalDateTime cutoff, long deadline) {
        GuardCandidate cursor = null;
        while (System.nanoTime() < deadline) {
            var candidates = guardCandidates(cutoff, cursor);
            if (candidates.isEmpty()) {
                return;
            }
            for (var candidate : candidates) {
                if (System.nanoTime() >= deadline) {
                    return;
                }
                cursor = candidate;
                try {
                    if (!markGuardAttempt(candidate)) {
                        continue;
                    }
                    metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
                    boolean applied
                        = "MEMBER".equals(candidate.type())
                            ? memberGuards
                                .reconcile(candidate.resourceId(), candidate.operationId(), candidate.expectedVersion())
                            : resourceGuards
                                .reconcile(
                                    candidate.type(),
                                    candidate.resourceId(),
                                    candidate.operationId(),
                                    candidate.expectedVersion()
                                );
                    metrics
                        .counter("scheduler.items", "job", JOB, "result", applied ? "success" : "pending")
                        .increment();
                } catch (RuntimeException failure) {
                    metrics.counter("scheduler.items", "job", JOB, "result", "failure").increment();
                    log
                        .warn(
                            "자원 작업 대사 미확인: resourceType={}, resourceId={}, exceptionType={}",
                            candidate.type(),
                            candidate.resourceId(),
                            failure.getClass().getSimpleName()
                        );
                }
            }
        }
    }

    /** 가드·스냅샷 갱신 시각을 건드리지 않고 현재 준비 작업의 대사 시도만 기록한다. */
    private boolean markGuardAttempt(GuardCandidate candidate) {
        var attemptedAt = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        return jdbc
            .update(
                "UPDATE contest_resource_guards SET last_reconciliation_attempt_at=? WHERE id=? AND operation_id=? "
                    + "AND version=? AND COALESCE(last_reconciliation_attempt_at,prepared_at,created_at)=? "
                    + "AND state='PREPARED' AND operation_result='PENDING'",
                attemptedAt,
                candidate.id(),
                candidate.operationId(),
                candidate.version(),
                candidate.reconciliationTime()
            ) == 1;
    }

    /** 스냅샷 갱신과 독립된 시도 시각으로 정렬하고 이번 회차에서 지나간 위치를 제외한다. */
    private java.util.List<GuardCandidate> guardCandidates(LocalDateTime cutoff, GuardCandidate cursor) {
        String reconciliationTime = "COALESCE(last_reconciliation_attempt_at,prepared_at,created_at)";
        String query
            = "SELECT id,resource_type,resource_id,operation_id,expected_source_version,version," + reconciliationTime
                + " AS reconciliation_time "
                + "FROM contest_resource_guards WHERE ((resource_type='MEMBER' AND operation_type='WITHDRAWAL') "
                + "OR (resource_type='STUDY' AND operation_type='STUDY_CLOSE') "
                + "OR (resource_type='PROBLEM' AND operation_type='PROBLEM_HIDE')) "
                + "AND state='PREPARED' AND operation_result='PENDING' AND " + reconciliationTime + "<=? ";
        org.springframework.jdbc.core.RowMapper<GuardCandidate> mapper
            = (rs, row) -> new GuardCandidate(
                rs.getLong(1),
                rs.getString(2),
                rs.getLong(3),
                rs.getString(4),
                rs.getLong(5),
                rs.getLong(6),
                rs.getObject(7, LocalDateTime.class)
            );
        String order = "ORDER BY reconciliation_time,id LIMIT 100";
        if (cursor == null) {
            return jdbc.query(query + order, mapper, cutoff);
        }
        return jdbc
            .query(
                query + "AND (" + reconciliationTime + ">? OR (" + reconciliationTime + "=? AND id>?)) " + order,
                mapper,
                cutoff,
                cursor.reconciliationTime(),
                cursor.reconciliationTime(),
                cursor.id()
            );
    }

    /** 원격 확인이 실패하더라도 시도 순서는 DB에 남긴다. */
    private boolean markRetryAttempt(Candidate candidate, LocalDateTime cutoff) {
        var attemptedAt = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        return jdbc
            .update(
                "UPDATE contest_admin_operations SET updated_at=? WHERE id=? AND state='PENDING' AND updated_at<=?",
                attemptedAt,
                candidate.id(),
                cutoff
            ) == 1;
    }

    private java.util.List<Candidate> candidates(LocalDateTime cutoff, Candidate cursor) {
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
                cursor.updatedAt(),
                cursor.updatedAt(),
                cursor.id()
            );
    }

    private record Candidate(long id, String operationId, LocalDateTime updatedAt) {
    }

    /** 현재 가드의 준비 작업과 이번 회차의 정렬 위치. */
    private record GuardCandidate(
        long id,
        String type,
        long resourceId,
        String operationId,
        long expectedVersion,
        long version,
        LocalDateTime reconciliationTime
    ) {
    }
}

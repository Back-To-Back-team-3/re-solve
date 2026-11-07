package com.backtoback.contest.exam.service;

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
import com.backtoback.contest.exam.client.ExamReceiptClient;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 시험 미종결 접수의 Judge 상태를 조회하고 기존 요청을 복구한다. */
@Component
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableScheduling
@Import(SchedulerLockConfiguration.class)
public class ContestSubmissionReconciliationScheduler {
    private static final String JOB = "ContestSubmissionReconciliationScheduler";
    private static final Logger log = LoggerFactory.getLogger(ContestSubmissionReconciliationScheduler.class);
    private final JdbcTemplate jdbc;
    private final ExamReceiptClient client;
    private final ExamReceiptReconciliation reconciliation;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ExamSchedulerCursorStore cursors;
    private final AtomicLong oldest = new AtomicLong();

    /** 대사 지표와 의존성을 구성한다. */
    public ContestSubmissionReconciliationScheduler(
        JdbcTemplate jdbc,
        ExamReceiptClient client,
        ExamReceiptReconciliation reconciliation,
        Clock clock,
        MeterRegistry metrics,
        ExamSchedulerCursorStore cursors
    ) {
        this.jdbc = jdbc;
        this.client = client;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.metrics = metrics;
        this.cursors = cursors;
        Gauge.builder("scheduler.backlog.oldest.seconds", oldest, AtomicLong::get).tag("job", JOB).register(metrics);
    }

    /** 원격 호출은 잠금 밖에서 수행하고 개별 접수 실패는 다음 회차에 재시도한다. */
    @Scheduled(
        fixedDelay = 300000,
        initialDelay = 300000
    )
    @SchedulerLock(
        name = "ContestSubmissionReconciliationScheduler",
        lockAtMostFor = "PT30S"
    )
    public void run() {
        var sample = Timer.start(metrics);
        try {
            var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
            var candidates = candidates(now.minusMinutes(1), null);
            oldest
                .set(
                    candidates.isEmpty() ? 0
                        : Math.max(0, Duration.between(candidates.getFirst().receivedAt(), now).getSeconds())
                );
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var cursor = cursors.load(JOB);
            while (System.nanoTime() < deadline) {
                candidates = cursor == null ? candidates : candidates(now.minusMinutes(1), cursor);
                if (candidates.isEmpty()) {
                    cursors.clear(JOB);
                    return;
                }
                for (var candidate : candidates) {
                    if (System.nanoTime() >= deadline) {
                        return;
                    }
                    metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
                    try {
                        var remote = client.find(candidate.id());
                        reconciliation.reconcile(candidate.id(), remote);
                        metrics.counter("scheduler.items", "job", JOB, "result", "success").increment();
                    } catch (RuntimeException failure) {
                        metrics.counter("scheduler.items", "job", JOB, "result", "failure").increment();
                        log
                            .error(
                                "시험 접수 대사 실패: receiptId={}, exceptionType={}",
                                candidate.id(),
                                failure.getClass().getSimpleName()
                            );
                    }
                    cursor = new ExamSchedulerCursorStore.Cursor(candidate.receivedAt(), candidate.id());
                    cursors.save(JOB, cursor);
                }
            }
        } finally {
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    /** 마지막 처리 시각과 ID 다음부터 한 페이지를 선택한다. */
    private java.util.List<Candidate> candidates(LocalDateTime cutoff, ExamSchedulerCursorStore.Cursor cursor) {
        String query
            = "SELECT s.id,s.received_at FROM exam_submissions s JOIN exams e ON e.id=s.exam_id "
                + "WHERE e.status<>'FINALIZED' AND s.received_at<=? "
                + "AND (s.status IN ('ACCEPTED','REQUESTED') OR (s.status='FAILED' AND s.is_zero_confirmed=false)) ";
        if (cursor == null) {
            return jdbc
                .query(
                    query + "ORDER BY s.received_at,s.id LIMIT 100",
                    (rs, row) -> new Candidate(rs.getLong(1), rs.getObject(2, LocalDateTime.class)),
                    cutoff
                );
        }
        return jdbc
            .query(
                query + "AND (s.received_at>? OR (s.received_at=? AND s.id>?)) "
                    + "ORDER BY s.received_at,s.id LIMIT 100",
                (rs, row) -> new Candidate(rs.getLong(1), rs.getObject(2, LocalDateTime.class)),
                cutoff,
                cursor.time(),
                cursor.time(),
                cursor.id()
            );
    }

    /** 대사 후보의 식별자와 원래 접수 시각이다. */
    private record Candidate(long id, LocalDateTime receivedAt) {
    }
}

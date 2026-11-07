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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 종료된 시험의 최초 결과 확정을 재시도한다 (스케줄러 §4.3). */
@Component
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableScheduling
@Import(SchedulerLockConfiguration.class)
public class ExamResultFinalizationScheduler {
    private static final String JOB = "ExamResultFinalizationScheduler";
    private static final Logger log = LoggerFactory.getLogger(ExamResultFinalizationScheduler.class);
    private final JdbcTemplate jdbc;
    private final ExamFinalizationService finalization;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicLong oldest = new AtomicLong();

    /** 확정 대기 시간 지표를 등록한다. */
    public ExamResultFinalizationScheduler(
        JdbcTemplate jdbc,
        ExamFinalizationService finalization,
        Clock clock,
        MeterRegistry metrics
    ) {
        this.jdbc = jdbc;
        this.finalization = finalization;
        this.clock = clock;
        this.metrics = metrics;
        Gauge.builder("scheduler.backlog.oldest.seconds", oldest, AtomicLong::get).tag("job", JOB).register(metrics);
    }

    /** 시험별 트랜잭션에서 조건을 다시 확인하고 실패와 보류를 분리한다. */
    @Scheduled(
        fixedDelay = 10000,
        initialDelay = 10000
    )
    @SchedulerLock(
        name = "ExamResultFinalizationScheduler",
        lockAtMostFor = "PT30S"
    )
    public void run() {
        var sample = Timer.start(metrics);
        try {
            var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
            var candidates
                = jdbc
                    .query(
                        "SELECT id,closed_at FROM exams WHERE status='CLOSED' " + "ORDER BY closed_at,id LIMIT 100",
                        (rs, row) -> new Candidate(rs.getLong(1), rs.getObject(2, LocalDateTime.class))
                    );
            oldest
                .set(
                    candidates.isEmpty() ? 0
                        : Math.max(0, Duration.between(candidates.getFirst().closedAt(), now).getSeconds())
                );
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            for (var candidate : candidates) {
                if (System.nanoTime() >= deadline) {
                    break;
                }
                metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
                try {
                    boolean completed = finalization.finalizeExam(candidate.id());
                    metrics
                        .counter("scheduler.items", "job", JOB, "result", completed ? "success" : "deferred")
                        .increment();
                } catch (RuntimeException failure) {
                    metrics.counter("scheduler.items", "job", JOB, "result", "failure").increment();
                    log
                        .error(
                            "시험 결과 확정 실패: examId={}, exceptionType={}",
                            candidate.id(),
                            failure.getClass().getSimpleName()
                        );
                }
            }
        } finally {
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    /** 종료 시각 순으로 선정한 시험이다. */
    private record Candidate(long id, LocalDateTime closedAt) {
    }
}

package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import com.backtoback.contest.exam.store.ExamSchedulerCursorStore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 마감된 입장자의 누락된 자동 제출을 재시도한다 (스케줄러 §4.2). */
@Component
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableScheduling
@Import(SchedulerLockConfiguration.class)
public class ExamAutoSubmissionScheduler {
    private static final String JOB = "ExamAutoSubmissionScheduler";
    private static final Logger log = LoggerFactory.getLogger(ExamAutoSubmissionScheduler.class);
    private final JdbcTemplate jdbc;
    private final ExamAutoSubmissionService submissions;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicLong overdue = new AtomicLong();
    private final AtomicLong oldestBacklog = new AtomicLong();
    private final ExamSchedulerCursorStore cursors;

    /** 자동 제출의 누락 건수 지표를 등록한다. */
    public ExamAutoSubmissionScheduler(
        JdbcTemplate jdbc,
        ExamAutoSubmissionService submissions,
        Clock clock,
        MeterRegistry metrics,
        ExamSchedulerCursorStore cursors
    ) {
        this.jdbc = jdbc;
        this.submissions = submissions;
        this.clock = clock;
        this.metrics = metrics;
        this.cursors = cursors;
        Gauge.builder("exam.auto_submission.overdue", overdue, AtomicLong::get).register(metrics);
        Gauge
            .builder("scheduler.backlog.oldest.seconds", oldestBacklog, AtomicLong::get)
            .tag("job", JOB)
            .register(metrics);
    }

    /** 후보를 100건까지 처리하고 각 참가자의 실패는 다음 실행에서 재시도한다. */
    @Scheduled(
        fixedDelay = 5000,
        initialDelay = 5000
    )
    @SchedulerLock(
        name = "ExamAutoSubmissionScheduler",
        lockAtMostFor = "PT30S"
    )
    public void run() {
        var sample = Timer.start(metrics);
        try {
            var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
            Long count
                = jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM exam_participants p JOIN exams e ON e.id=p.exam_id "
                            + "WHERE p.status='STARTED' AND p.entered_at IS NOT NULL AND p.auto_submitted_at IS NULL "
                            + "AND e.status<>'CANCELED' AND p.personal_ends_at < ?",
                        Long.class,
                        now.minusSeconds(30)
                    );
            overdue.set(count == null ? 0 : count);
            var cursor = cursors.load(JOB);
            var candidates = candidates(now.minusSeconds(3), cursor);
            if (candidates.isEmpty() && cursor != null) {
                cursors.clear(JOB);
                candidates = candidates(now.minusSeconds(3), null);
            }
            oldestBacklog
                .set(
                    candidates.isEmpty() ? 0 : Math
                        .max(
                            0,
                            Duration.between(candidates.getFirst().personalEndsAt().plusSeconds(3), now).getSeconds()
                        )
                );
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            for (var candidate : candidates) {
                if (System.nanoTime() >= deadline) {
                    break;
                }
                metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
                try {
                    boolean done = submissions.finishDue(candidate.exam(), candidate.member(), candidate.participant());
                    metrics.counter("scheduler.items", "job", JOB, "result", done ? "success" : "deferred").increment();
                } catch (RuntimeException failure) {
                    metrics.counter("scheduler.items", "job", JOB, "result", "failure").increment();
                    log
                        .error(
                            "시험 자동 제출 실패: participantId={}, exceptionType={}",
                            candidate.participant(),
                            failure.getClass().getSimpleName()
                        );
                }
                cursors
                    .save(
                        JOB,
                        new ExamSchedulerCursorStore.Cursor(candidate.personalEndsAt(), candidate.participant())
                    );
            }
        } finally {
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    private List<Candidate> candidates(LocalDateTime cutoff, ExamSchedulerCursorStore.Cursor cursor) {
        String query
            = "SELECT p.exam_id,p.member_id,p.id,p.personal_ends_at FROM exam_participants p "
                + "JOIN exams e ON e.id=p.exam_id WHERE p.status='STARTED' AND p.entered_at IS NOT NULL "
                + "AND p.auto_submitted_at IS NULL AND e.status<>'CANCELED' AND p.personal_ends_at<=? ";
        var arguments = new ArrayList<Object>();
        arguments.add(cutoff);
        if (cursor != null) {
            query += "AND (p.personal_ends_at>? OR (p.personal_ends_at=? AND p.id>?)) ";
            arguments.add(cursor.time());
            arguments.add(cursor.time());
            arguments.add(cursor.id());
        }
        return jdbc
            .query(
                query + "ORDER BY p.personal_ends_at,p.id LIMIT 100",
                (rs, row) -> new Candidate(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getObject(4, LocalDateTime.class)
                ),
                arguments.toArray()
            );
    }

    /** 잠금 전에 선택한 참가 식별자다. */
    private record Candidate(long exam, long member, long participant, LocalDateTime personalEndsAt) {
    }
}

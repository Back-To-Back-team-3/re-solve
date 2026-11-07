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
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.common.scheduling.config.SchedulerLockConfiguration;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 대상 시험을 한 회차에 최대 100건 선정해 각각 독립적으로 전이한다. */
@Component
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableScheduling
@Import(SchedulerLockConfiguration.class)
public class ExamLifecycleScheduler {
    private static final String JOB = "ExamLifecycleScheduler";
    private static final Logger log = LoggerFactory.getLogger(ExamLifecycleScheduler.class);
    private final ExamRepository exams;
    private final ExamLifecycleService lifecycle;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicLong oldestBacklog = new AtomicLong();

    /** 시험 전이 의존성과 회차별 최장 대기 지표를 등록한다. */
    public ExamLifecycleScheduler(
        ExamRepository exams,
        ExamLifecycleService lifecycle,
        Clock clock,
        MeterRegistry metrics
    ) {
        this.exams = exams;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.metrics = metrics;
        Gauge
            .builder("scheduler.backlog.oldest.seconds", oldestBacklog, AtomicLong::get)
            .tag("job", JOB)
            .register(metrics);
    }

    /** 20초 예산 내에서 선정한 각 시험을 처리하고 실패한 시험은 다음 회차에 다시 선정한다. */
    @Scheduled(
        fixedDelay = 5000,
        initialDelay = 5000
    )
    @SchedulerLock(
        name = "ExamLifecycleScheduler",
        lockAtMostFor = "PT30S"
    )
    public void run() {
        var sample = Timer.start(metrics);
        try {
            processCandidates();
        } finally {
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    /** 시각과 ID 순으로 선정한 시험을 각각 처리하며 실패를 분리한다. */
    private void processCandidates() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        var ids = exams.findLifecycleCandidates(now, PageRequest.of(0, 100));
        oldestBacklog.set(ids.isEmpty() ? 0 : exams.findById(ids.getFirst()).map(exam -> {
            var due = exam.getStatus() == ExamStatus.SCHEDULED ? exam.getStartsAt() : exam.getEndsAt();
            return Math.max(0, Duration.between(due, now).getSeconds());
        }).orElse(0L));
        for (long id : ids) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            metrics.counter("scheduler.items", "job", JOB, "result", "selected").increment();
            try {
                lifecycle.transition(id);
                metrics.counter("scheduler.items", "job", JOB, "result", "success").increment();
            } catch (RuntimeException failure) {
                metrics.counter("scheduler.items", "job", JOB, "result", "failure").increment();
                log.error("시험 생명주기 전이 실패: examId={}", id, failure);
            }
        }
    }
}

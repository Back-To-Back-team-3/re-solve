package com.backtoback.contest.exam.service;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;

import com.backtoback.common.scheduling.config.SchedulerLockConfiguration;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.repository.ExamRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

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
    private final ScheduledExecutorService lockRenewals = Executors.newSingleThreadScheduledExecutor();
    private final LockProvider lifecycleLocks;
    private LocalDateTime afterDue;
    private long afterId;

    /** 시험 전이 의존성과 회차별 최장 대기 지표를 등록한다. */
    public ExamLifecycleScheduler(
        ExamRepository exams,
        ExamLifecycleService lifecycle,
        Clock clock,
        MeterRegistry metrics,
        LockProvider lockProvider
    ) {
        this.exams = exams;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.metrics = metrics;
        this.lifecycleLocks = new KeepAliveLockProvider(lockProvider, lockRenewals);
        Gauge
            .builder("scheduler.backlog.oldest.seconds", oldestBacklog, AtomicLong::get)
            .tag("job", JOB)
            .register(metrics);
    }

    /** 시험 전이가 계속되는 동안 이 배치의 ShedLock 임대를 갱신한다. */
    @Scheduled(
        fixedDelay = 5000,
        initialDelay = 5000
    )
    public void run() {
        var lock
            = lifecycleLocks.lock(new LockConfiguration(clock.instant(), JOB, Duration.ofSeconds(30), Duration.ZERO));
        if (lock.isEmpty()) {
            return;
        }
        var sample = Timer.start(metrics);
        try {
            processCandidates();
        } finally {
            lock.get().unlock();
            sample.stop(metrics.timer("scheduler.run.duration", "job", JOB));
        }
    }

    @PreDestroy
    void stopRenewals() {
        lockRenewals.shutdown();
    }

    /** 시각과 ID 순으로 선정한 시험을 각각 처리하며 실패를 분리한다. */
    private void processCandidates() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        var firstPage = PageRequest.of(0, 100);
        var candidates = exams.findLifecycleCandidates(now, afterDue, afterId, firstPage);
        if (candidates.isEmpty() && afterDue != null) {
            afterDue = null;
            afterId = 0;
            candidates = exams.findLifecycleCandidates(now, null, 0, firstPage);
        }
        if (afterDue == null) {
            oldestBacklog
                .set(
                    candidates.isEmpty() ? 0
                        : Math.max(0, Duration.between(candidates.getFirst().getDueAt(), now).getSeconds())
                );
        }
        for (var candidate : candidates) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            // 실패한 시험도 통과 지점에 포함해 뒤 후보에 처리 기회를 주고, 끝에서 처음으로 돌아와 재시도한다.
            afterDue = candidate.getDueAt();
            afterId = candidate.getId();
            long id = afterId;
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

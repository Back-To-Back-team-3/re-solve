package com.backtoback.problem.asset.scheduler;

import java.time.Clock;
import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.problem.asset.service.AssetCleanupService;
import com.backtoback.problem.asset.service.AssetCleanupStore;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 매일 미연결 네 종류 파일을 건별로 정리하고 DELETING을 복구한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnlinkedAssetCleanupScheduler {
    private static final Duration START_BUDGET = Duration.ofMinutes(29);
    private final AssetCleanupStore store;
    private final AssetCleanupService cleanup;
    private final MeterRegistry metrics;
    private final Clock clock;

    /** 30분 잠금 만료 전에 새 건의 시작을 중단한다(스케줄러 §2.1). */
    @Scheduled(
        cron = "0 30 4 * * *",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = "UnlinkedAssetCleanupScheduler",
        lockAtMostFor = "PT30M"
    )
    public void clean() {
        LockAssert.assertLocked();
        var sample = Timer.start(metrics);
        long start = System.nanoTime();
        var deadline = clock.instant().plus(START_BUDGET);
        try {
            for (var candidate : store.candidates()) {
                if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)
                    || System.nanoTime() - start >= START_BUDGET.toNanos()) {
                    break;
                }
                LockAssert.assertLocked();
                String result;
                try {
                    result = cleanup.clean(candidate);
                } catch (RuntimeException failure) {
                    result = "failed";
                    log.warn("파일 정리 실패: kind={}, id={}", candidate.kind(), candidate.id(), failure);
                }
                metrics.counter("problem.asset.cleanup.items", "outcome", result).increment();
            }
            metrics.summary("problem.asset.cleanup.oldest.seconds", "state", "unlinked").record(store.oldestAge(false));
            metrics.summary("problem.asset.cleanup.oldest.seconds", "state", "deleting").record(store.oldestAge(true));
        } finally {
            sample.stop(metrics.timer("problem.asset.cleanup.duration"));
        }
    }
}

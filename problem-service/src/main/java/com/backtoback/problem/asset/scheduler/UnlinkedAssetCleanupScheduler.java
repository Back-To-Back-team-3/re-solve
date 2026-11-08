package com.backtoback.problem.asset.scheduler;

import java.time.Clock;
import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.problem.asset.service.AssetCleanupBudget;
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
    private final AssetCleanupStore store;
    private final AssetCleanupService cleanup;
    private final MeterRegistry metrics;
    private final Clock clock;

    /** 잠금의 절반인 15분 예산을 모든 원격·DB 단계에 공유한다(스케줄러 §0.1·§2.1). */
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
        var budget = new AssetCleanupBudget(clock);
        try {
            for (var candidate : store.candidates()) {
                if (!budget.hasTime(Duration.ofSeconds(10))) {
                    break;
                }
                LockAssert.assertLocked();
                String result;
                try {
                    result = cleanup.clean(candidate, budget);
                } catch (RuntimeException failure) {
                    result = "failed";
                    log.warn("파일 정리 실패: kind={}, id={}", candidate.kind(), candidate.id(), failure);
                }
                metrics.counter("problem.asset.cleanup.items", "outcome", result).increment();
            }
            if (budget.hasTime(Duration.ofSeconds(10))) {
                metrics
                    .summary("problem.asset.cleanup.oldest.seconds", "state", "unlinked")
                    .record(store.oldestAge(false));
            }
            if (budget.hasTime(Duration.ofSeconds(10))) {
                metrics
                    .summary("problem.asset.cleanup.oldest.seconds", "state", "deleting")
                    .record(store.oldestAge(true));
            }
        } finally {
            sample.stop(metrics.timer("problem.asset.cleanup.duration"));
        }
    }
}

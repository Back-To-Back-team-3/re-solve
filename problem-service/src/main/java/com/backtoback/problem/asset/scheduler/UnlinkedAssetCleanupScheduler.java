package com.backtoback.problem.asset.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backtoback.problem.asset.service.AssetCleanupBudget;
import com.backtoback.problem.asset.service.AssetCleanupService;
import com.backtoback.problem.asset.service.AssetCleanupStore;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
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
    private final AtomicLong oldestUnlinked = new AtomicLong();
    private final AtomicLong oldestDeleting = new AtomicLong();

    /** 미연결·삭제 중 파일의 마지막 조회값을 각각 Gauge로 등록한다. */
    @PostConstruct
    void registerOldestAgeGauges() {
        metrics.gauge("problem.asset.cleanup.oldest.seconds", Tags.of("state", "unlinked"), oldestUnlinked);
        metrics.gauge("problem.asset.cleanup.oldest.seconds", Tags.of("state", "deleting"), oldestDeleting);
    }

    /** 잠금의 절반인 15분 예산을 모든 원격·DB 단계에 공유한다(스케줄러 §0.1·§2.1). */
    @Scheduled(
        cron = "0 30 4 * * *",
        zone = "Asia/Seoul"
    )
    public void clean() {
        var budget = new AssetCleanupBudget(clock);
        var lease = store.acquireLease();
        if (lease.isEmpty()) {
            return;
        }
        var owner = lease.get();
        var sample = Timer.start(metrics);
        try {
            if (!budget.hasTime(Duration.ofSeconds(10)) || !store.isCurrentLease(owner)) {
                return;
            }
            for (var candidate : store.candidates()) {
                if (!budget.hasTime(Duration.ofSeconds(10))) {
                    break;
                }
                String result;
                try {
                    result = cleanup.clean(candidate, budget, owner);
                } catch (RuntimeException failure) {
                    result = "failed";
                    log.warn("파일 정리 실패: kind={}, id={}", candidate.kind(), candidate.id(), failure);
                }
                metrics.counter("problem.asset.cleanup.items", "outcome", result).increment();
            }
            if (budget.hasTime(Duration.ofSeconds(10))) {
                oldestUnlinked.set(store.oldestAge(false));
            }
            if (budget.hasTime(Duration.ofSeconds(10))) {
                oldestDeleting.set(store.oldestAge(true));
            }
        } finally {
            try {
                store.releaseLease(owner);
            } finally {
                sample.stop(metrics.timer("problem.asset.cleanup.duration"));
            }
        }
    }
}

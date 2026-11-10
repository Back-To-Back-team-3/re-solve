package com.backtoback.judge.run.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.judge.run.store.RunLockStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 여러 인스턴스 중 한 곳에서 중단된 Run 제한을 정리한다. (스케줄러 §3.5) */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunLockReconciliationScheduler {
    private final RunLockStore locks;

    /** Redis 장애 시 실행 중이라고 보존하고 다음 회차에 다시 확인한다. */
    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(
        name = "RunLockReconciliationScheduler",
        lockAtMostFor = "PT50S"
    )
    public void reconcile() {
        try {
            locks.reconcile();
        } catch (RuntimeException failure) {
            log.warn("Run 제한 정리 실패: type={}", failure.getClass().getSimpleName());
        }
    }
}

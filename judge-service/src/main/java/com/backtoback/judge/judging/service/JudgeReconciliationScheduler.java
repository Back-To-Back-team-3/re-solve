package com.backtoback.judge.judging.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 최신 작업을 근거로 미종결 제출의 상태를 건별 보정하고 근거 없는 누락은 운영에 알린다. */
@Component
@ConditionalOnProperty(
    name = "judge.messaging.enabled",
    havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class JudgeReconciliationScheduler {
    private static final String SCHEDULER = "JudgeReconciliationScheduler";
    private final SubmissionExecutionStore store;
    private final SubmissionJudgingDataClient dataClient;
    private final MeterRegistry meters;
    private long lastSubmissionId;

    /** 원격 자료 조회 없이 공통 적체 지표의 제한시간 있는 DB 조회를 등록한다. */
    @PostConstruct
    void registerBacklogGauge() {
        Gauge
            .builder(
                "scheduler.backlog.oldest.seconds",
                store,
                SubmissionExecutionStore::reconciliationBacklogAgeSeconds
            )
            .tag("scheduler", SCHEDULER)
            .register(meters);
    }

    /** 원격 조회는 잠금 밖에서 수행하고 건별 장애를 격리한다. (스케줄러 §0.1·§3.4) */
    @Scheduled(
        fixedDelay = 300000,
        initialDelay = 300000,
        scheduler = "judgeMaintenanceScheduler"
    )
    @SchedulerLock(
        name = "JudgeReconciliationScheduler",
        lockAtMostFor = "4m"
    )
    public void reconcile() {
        var sample = Timer.start(meters);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        try (var ignored = MDC.putCloseable("correlationId", "sched-JudgeReconciliation-" + UUID.randomUUID())) {
            var candidates = store.reconciliationCandidates(lastSubmissionId);
            if (candidates.isEmpty()) {
                lastSubmissionId = 0;
                return;
            }
            for (var candidate : candidates) {
                if (deadline - System.nanoTime() < TimeUnit.SECONDS.toNanos(15)) {
                    break;
                }
                // 실패한 후보도 지나가고 다음 회차에 뒤의 후보를 계속 확인한다.
                lastSubmissionId = candidate.submissionId();
                try {
                    if (candidate.jobId() == null) {
                        boolean missing = store.missingReconciliationJob(candidate);
                        recordItem(missing ? "missing" : "skipped");
                        if (missing) {
                            meters.counter("judge.reconciliation.missing.jobs").increment();
                            log
                                .warn(
                                    "채점 상태 대사에서 최신 작업 누락: submissionId={}, judgeAttempt={}",
                                    candidate.submissionId(),
                                    candidate.judgeAttempt()
                                );
                        }
                        continue;
                    }
                    var data
                        = "SUCCEEDED".equals(candidate.jobStatus())
                            ? dataClient.find(candidate.problemId(), candidate.revisionId()) : null;
                    recordItem(store.reconcile(candidate, data) ? "corrected" : "skipped");
                } catch (RuntimeException failure) {
                    recordItem("failed");
                    log.warn("채점 상태 대사 실패: submissionId={}", candidate.submissionId(), failure);
                }
            }
        } finally {
            sample.stop(Timer.builder("scheduler.run.duration").tag("scheduler", SCHEDULER).register(meters));
        }
    }

    /**
     * 실제 확인한 후보의 처리 결과를 공통 지표로 기록한다.
     *
     * @param result 보정·건너뜀·실패·작업 누락 구분
     */
    private void recordItem(String result) {
        meters.counter("scheduler.items", "scheduler", SCHEDULER, "result", result).increment();
    }
}

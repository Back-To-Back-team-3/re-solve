package com.backtoback.integration.github.worker;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.github.service.GithubSyncExecutionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PENDING, RETRY_WAITING, RATE_LIMITED 상태의 Sync Job을 SKIP LOCKED로 선점하고 실행을 위임하는 워커 (scheduler.md > 8.1).
 * 작업 1건씩 선점 트랜잭션에서 RUNNING으로 변경 후 즉시 실행하여 실제 작업 시작 시각을 보장하고,
 * 외부 HTTP 호출을 트랜잭션 밖에서 수행하여 DB 커넥션 점유를 최소화한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubSyncJobWorker {

    public static final int BATCH_SIZE = 10;

    private final GithubSyncJobRepository syncJobRepository;
    private final GithubSyncExecutionService executionService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 5초 주기로 선점 가능한 작업을 1건씩 선점하여 즉시 실행한다 (회차당 최대 10건).
     * 각 작업마다 실행 직전에 선점하여 RUNNING으로 전이하므로, 앞선 작업의 지연으로
     * 뒤쪽 작업이 정체 작업(5분 초과)으로 오탐되는 문제를 원천 차단한다.
     */
    @Scheduled(
        fixedDelayString = "${integration.github.sync-worker.fixed-delay-ms:5000}",
        initialDelayString = "${integration.github.sync-worker.initial-delay-ms:5000}"
    )
    public void pollAndExecuteSyncJobs() {
        int processedCount = 0;
        while (processedCount < BATCH_SIZE) {
            Optional<Long> claimedJobIdOpt = claimNextJob();
            if (claimedJobIdOpt.isEmpty()) {
                break;
            }

            Long jobId = claimedJobIdOpt.get();
            try {
                executionService.executeSyncJob(jobId);
            } catch (Exception e) {
                log.error("Sync Job 실행 중 미처리 예외 발생: jobId={}", jobId, e);
            }
            processedCount++;
        }

        if (processedCount > 0) {
            log.info("Sync Job 폴링 처리 완료: processedCount={}", processedCount);
        }
    }

    /**
     * SKIP LOCKED로 대기 작업 1건을 선점하고 RUNNING 상태로 전이시킨 후 ID를 반환한다 (단건 선점 트랜잭션).
     *
     * @return 선점된 작업 ID Optional
     */
    public Optional<Long> claimNextJob() {
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<GithubSyncJob> claimableJobOpt = syncJobRepository.findNextClaimableJobForUpdateSkipLocked(now);
            if (claimableJobOpt.isEmpty()) {
                return null;
            }

            GithubSyncJob job = claimableJobOpt.get();
            job.markRunning();
            return job.getId();
        }));
    }
}

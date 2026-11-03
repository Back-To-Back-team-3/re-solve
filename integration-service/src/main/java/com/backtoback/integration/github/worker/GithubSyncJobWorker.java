package com.backtoback.integration.github.worker;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

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
 * 선점 트랜잭션과 실제 GitHub 커밋 실행 트랜잭션을 분리하여 DB 커넥션 점유를 최소화한다.
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
     * 5초 주기로 선점 가능한 작업을 배치 단위(최대 10건)로 조회하여 실행한다.
     */
    @Scheduled(
        fixedDelayString = "${integration.github.sync-worker.fixed-delay-ms:5000}",
        initialDelayString = "${integration.github.sync-worker.initial-delay-ms:5000}"
    )
    public void pollAndExecuteSyncJobs() {
        List<Long> claimedJobIds = claimJobsBatch(BATCH_SIZE);
        if (claimedJobIds.isEmpty()) {
            return;
        }

        log.info("Sync Job {}건 선점 완료, 순차 실행 시작: jobIds={}", claimedJobIds.size(), claimedJobIds);
        for (Long jobId : claimedJobIds) {
            try {
                executionService.executeSyncJob(jobId);
            } catch (Exception e) {
                log.error("Sync Job 실행 중 미처리 예외 발생: jobId={}", jobId, e);
            }
        }
    }

    /**
     * SKIP LOCKED로 대기 작업을 선점하고 RUNNING 상태로 전이시킨 후 ID 목록을 반환한다 (선점 트랜잭션).
     *
     * @param limit 최대 선점 건수
     * @return 선점된 작업 ID 목록
     */
    public List<Long> claimJobsBatch(int limit) {
        List<Long> claimed = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            List<GithubSyncJob> claimableJobs = syncJobRepository.findClaimableJobsForUpdateSkipLocked(now, limit);
            if (claimableJobs == null || claimableJobs.isEmpty()) {
                return Collections.emptyList();
            }

            for (GithubSyncJob job : claimableJobs) {
                job.markRunning();
            }
            return claimableJobs.stream().map(GithubSyncJob::getId).toList();
        });

        return claimed != null ? claimed : Collections.emptyList();
    }
}

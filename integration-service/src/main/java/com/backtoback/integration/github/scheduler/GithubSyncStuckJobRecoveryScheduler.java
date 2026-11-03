package com.backtoback.integration.github.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.github.service.GithubSyncExecutionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실행 중(RUNNING) 상태에서 5분 이상 정체된 작업을 감지하여 재시도 대기(RETRY_WAITING) 또는
 * 최종 실패(FAILED)로 복구하는 스케줄러 (scheduler.md > 8.2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubSyncStuckJobRecoveryScheduler {

    public static final Duration STUCK_DURATION = Duration.ofMinutes(5);
    public static final int MAX_RETRY_COUNT = GithubSyncExecutionService.MAX_RETRY_COUNT;

    private final GithubSyncJobRepository syncJobRepository;
    private final GithubSyncExecutionService executionService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 1분 주기로 5분 이상 갱신되지 않은 RUNNING 작업을 조회하여 복구한다.
     */
    @Scheduled(
        fixedDelayString = "${integration.github.stuck-recovery.fixed-delay-ms:60000}",
        initialDelayString = "${integration.github.stuck-recovery.initial-delay-ms:10000}"
    )
    @SchedulerLock(
        name = "GitHubSyncStuckJobRecoveryScheduler",
        lockAtMostFor = "PT50S",
        lockAtLeastFor = "PT10S"
    )
    public void recoverStuckJobs() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minus(STUCK_DURATION);

        List<GithubSyncJob> stuckJobs
            = syncJobRepository.findByStatusAndUpdatedAtBeforeWithConnection(GithubSyncJobStatus.RUNNING, cutoff);
        if (stuckJobs.isEmpty()) {
            return;
        }

        log.warn("장기 정체된 Sync Job {}건 발견, 복구 시작: cutoff={}", stuckJobs.size(), cutoff);
        for (GithubSyncJob job : stuckJobs) {
            recoverStuckJob(job, now);
        }
    }

    private void recoverStuckJob(GithubSyncJob job, LocalDateTime now) {
        Long jobId = job.getId();
        LocalDateTime observedUpdatedAt = job.getUpdatedAt();
        int currentRetryCount = job.getRetryCount();
        Long memberId = job.getConnection().getMemberId();
        Long submissionId = job.getSubmissionId();
        Long problemId = job.getProblemId();
        String problemTitle = job.getProblemTitle();

        transactionTemplate.executeWithoutResult(status -> {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                // 5회 초과 시 최종 실패 및 Outbox 이벤트 발행
                int affected
                    = syncJobRepository
                        .recoverStuckJobToFailed(jobId, observedUpdatedAt, "장기 정체로 인한 실패 처리 (5회 초과)", now);
                if (affected == 1) {
                    executionService
                        .publishSyncFailedOutboxEvent(
                            jobId,
                            memberId,
                            submissionId,
                            problemId,
                            problemTitle,
                            "STUCK_TIMEOUT",
                            now
                        );
                    log.warn("장기 정체 Sync Job 최대 재시도 초과로 FAILED 처리: jobId={}", jobId);
                }
            } else {
                // 1분 후 재시도 대기
                LocalDateTime nextRetryAt = now.plusMinutes(1);
                int affected
                    = syncJobRepository
                        .recoverStuckJobToRetryWaiting(
                            jobId,
                            observedUpdatedAt,
                            nextRetryAt,
                            "장기 정체로 인한 자동 재시도 대기",
                            now
                        );
                if (affected == 1) {
                    log
                        .info(
                            "장기 정체 Sync Job 복구 완료(RETRY_WAITING): jobId={}, retryCount={}",
                            jobId,
                            currentRetryCount + 1
                        );
                }
            }
        });
    }
}

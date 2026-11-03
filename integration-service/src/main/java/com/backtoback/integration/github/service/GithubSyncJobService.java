package com.backtoback.integration.github.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubConnectionStatus;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * GitHub 자동 커밋 작업 등록 및 상태 관리 서비스 (erd.md > 4.8.2, policy-and-state.md > 9.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubSyncJobService {

    private static final List<GithubSyncJobStatus> CANCELABLE_STATUSES
        = List.of(GithubSyncJobStatus.PENDING, GithubSyncJobStatus.RETRY_WAITING, GithubSyncJobStatus.RATE_LIMITED);

    private final GithubConnectionRepository connectionRepository;
    private final GithubSyncJobRepository syncJobRepository;
    private final Clock clock;

    /**
     * AC 판정된 제출 건에 대해 회원의 연동 상태 및 자동 커밋 설정을 확인한 후 신규 Sync Job을 등록한다 (policy-and-state.md > 9.3).
     *
     * @param memberId 회원 ID
     * @param submissionId 제출 ID
     * @param problemId 문제 ID
     * @param problemTitle 문제명
     * @param problemDifficulty 문제 난이도 (1~3)
     * @return 생성된 작업 Optional (연동 미완료, 비활성화 또는 중복 시 empty)
     */
    @Transactional
    public Optional<GithubSyncJob> registerSyncJob(
        Long memberId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        int problemDifficulty
    ) {
        Optional<GithubConnection> connectionOpt = connectionRepository.findByMemberId(memberId);
        if (connectionOpt.isEmpty()) {
            log.debug("GitHub 연동 정보가 없어 Sync Job 생성을 생략합니다: memberId={}", memberId);
            return Optional.empty();
        }

        GithubConnection connection = connectionOpt.get();
        if (connection.getStatus() != GithubConnectionStatus.CONNECTED) {
            log.debug("GitHub 연동 상태가 CONNECTED가 아니므로 생략합니다: memberId={}, status={}", memberId, connection.getStatus());
            return Optional.empty();
        }

        if (!connection.isAutoCommitEnabled()) {
            log.debug("GitHub 자동 커밋 설정이 비활성화되어 생략합니다: memberId={}", memberId);
            return Optional.empty();
        }

        if (syncJobRepository.existsBySubmissionId(submissionId)) {
            log.info("이미 등록된 제출 동기화 작업이 존재합니다: submissionId={}", submissionId);
            return Optional.empty();
        }

        // 코드 포인트 단위 자르기 및 유효성 검증을 거친 후 조건부 삽입
        GithubSyncJob pendingJob
            = GithubSyncJob.createPending(connection, submissionId, problemId, problemTitle, problemDifficulty);

        LocalDateTime now = LocalDateTime.now(clock);
        syncJobRepository
            .insertPendingJobIfAbsent(
                connection.getId(),
                submissionId,
                problemId,
                pendingJob.getProblemTitle(),
                problemDifficulty,
                now
            );

        Optional<GithubSyncJob> savedJob = syncJobRepository.findBySubmissionId(submissionId);
        savedJob
            .ifPresent(
                job -> log
                    .info(
                        "GitHub Sync Job 등록 확인: jobId={}, memberId={}, submissionId={}, problemId={}",
                        job.getId(),
                        memberId,
                        submissionId,
                        problemId
                    )
            );
        return savedJob;
    }

    /**
     * 연동 해제 시 대기 및 재시도 상태인 Sync Job을 일괄 취소(CANCELED) 상태로 전이한다 (api-spec.md > 8.1.5, policy-and-state.md > 9.3).
     *
     * @param connectionId 연동 ID
     * @param reason 취소 사유
     * @return 취소된 작업 건수
     */
    @Transactional
    public int cancelPendingJobs(Long connectionId, String reason) {
        List<GithubSyncJob> pendingJobs
            = syncJobRepository.findByConnectionIdAndStatusIn(connectionId, CANCELABLE_STATUSES);
        for (GithubSyncJob job : pendingJobs) {
            job.cancel(reason);
        }
        if (!pendingJobs.isEmpty()) {
            log
                .info(
                    "대기 중인 GitHub Sync Job 취소 처리 완료: connectionId={}, count={}, reason={}",
                    connectionId,
                    pendingJobs.size(),
                    reason
                );
        }
        return pendingJobs.size();
    }
}

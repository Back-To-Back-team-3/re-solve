package com.backtoback.integration.github.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;

/**
 * GitHub 자동 커밋 작업 JPA 리포지토리 (erd.md > 4.8.2, scheduler.md > 8.1, 8.2).
 */
public interface GithubSyncJobRepository extends JpaRepository<GithubSyncJob, Long> {

    Optional<GithubSyncJob> findBySubmissionId(Long submissionId);

    boolean existsBySubmissionId(Long submissionId);

    List<GithubSyncJob> findByConnectionIdAndStatusIn(Long connectionId, Collection<GithubSyncJobStatus> statuses);

    /**
     * 실행 대기(PENDING, RETRY_WAITING, RATE_LIMITED) 상태의 작업을 다중 워커 간 경합 없이 선점하기 위해
     * SKIP LOCKED로 조회한다 (scheduler.md > 8.1).
     *
     * @param now 현재 시각
     * @param limit 최대 선점 건수
     * @return 선점된 GithubSyncJob 목록
     */
    @Query(
        value = """
            SELECT * FROM github_sync_jobs
            WHERE status IN ('PENDING', 'RETRY_WAITING', 'RATE_LIMITED')
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        nativeQuery = true
    )
    List<GithubSyncJob> findClaimableJobsForUpdateSkipLocked(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 실행 중(RUNNING) 상태에서 임계 시각을 초과한 정체 작업 목록을 조회한다 (scheduler.md > 8.2).
     *
     * @param status 작업 상태 (RUNNING)
     * @param cutoff 기준 시각
     * @return 정체된 GithubSyncJob 목록
     */
    List<GithubSyncJob> findByStatusAndUpdatedAtBefore(GithubSyncJobStatus status, LocalDateTime cutoff);
}

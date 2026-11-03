package com.backtoback.integration.github.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * 신규 작업을 조건부 삽입한다 (동일 submission_id 존재 시 무시).
     * 중복 시 유니크 제약 예외를 발생시키지 않아 상위 트랜잭션이 rollback-only로 오염되는 것을 방지한다.
     *
     * @param connectionId 연동 ID
     * @param submissionId 제출 ID
     * @param problemId 문제 ID
     * @param problemTitle 문제명 스냅샷
     * @param problemDifficulty 문제 난이도 스냅샷
     * @param now 생성/수정 시각
     * @return 영향받은 행 수
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO github_sync_jobs (
                connection_id, submission_id, problem_id, problem_title,
                problem_difficulty, status, retry_count, created_at, updated_at
            )
            VALUES (
                :connectionId, :submissionId, :problemId, :problemTitle,
                :problemDifficulty, 'PENDING', 0, :now, :now
            )
            ON DUPLICATE KEY UPDATE submission_id = submission_id
            """,
        nativeQuery = true
    )
    int insertPendingJobIfAbsent(
        @Param("connectionId") Long connectionId,
        @Param("submissionId") Long submissionId,
        @Param("problemId") Long problemId,
        @Param("problemTitle") String problemTitle,
        @Param("problemDifficulty") int problemDifficulty,
        @Param("now") LocalDateTime now
    );

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

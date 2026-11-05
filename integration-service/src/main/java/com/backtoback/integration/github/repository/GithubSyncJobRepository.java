package com.backtoback.integration.github.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 동일 문제(problemId)의 가장 최근 작업 스냅샷(문제명·난이도)을 조회한다 (api-spec.md > 8.1.7).
     *
     * @param problemId 문제 ID
     * @return 가장 최근 생성된 GithubSyncJob Optional
     */
    Optional<GithubSyncJob> findFirstByProblemIdOrderByIdDesc(Long problemId);

    /**
     * 제출 ID로 작업과 연동 정보(connection)를 함께 조회한다 (api-spec.md > 8.1.7).
     *
     * @param submissionId 제출 ID
     * @return 연동 정보가 포함된 GithubSyncJob
     */
    @Query("SELECT j FROM GithubSyncJob j JOIN FETCH j.connection WHERE j.submissionId = :submissionId")
    Optional<GithubSyncJob> findBySubmissionIdWithConnection(@Param("submissionId") Long submissionId);

    boolean existsBySubmissionId(Long submissionId);

    List<GithubSyncJob> findByConnectionIdAndStatusIn(Long connectionId, Collection<GithubSyncJobStatus> statuses);

    /**
     * 특정 연동 ID의 전체 동기화 작업 목록을 페이지네이션 조회한다 (api-spec.md > 8.1.6).
     *
     * @param connectionId 연동 ID
     * @param pageable 페이지 및 정렬 정보
     * @return 동기화 작업 페이지
     */
    Page<GithubSyncJob> findByConnectionId(Long connectionId, Pageable pageable);

    /**
     * 특정 연동 ID와 작업 상태로 필터링한 동기화 작업 목록을 페이지네이션 조회한다 (api-spec.md > 8.1.6).
     *
     * @param connectionId 연동 ID
     * @param status 작업 상태 필터
     * @param pageable 페이지 및 정렬 정보
     * @return 동기화 작업 페이지
     */
    Page<GithubSyncJob> findByConnectionIdAndStatus(Long connectionId, GithubSyncJobStatus status, Pageable pageable);

    /**
     * 연동 정보(connection)를 Fetch Join하여 LAZY 초기화 예외 없이 한 번에 조회한다.
     *
     * @param id 작업 식별자
     * @return 연동 정보가 포함된 GithubSyncJob
     */
    @Query("SELECT j FROM GithubSyncJob j JOIN FETCH j.connection WHERE j.id = :id")
    Optional<GithubSyncJob> findByIdWithConnection(@Param("id") Long id);

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
     * 실행 중(RUNNING) 상태인 작업에 대해 조건부로 성공(SUCCEEDED) 상태 및 커밋 SHA를 반영한다 (scheduler.md > 8.1).
     * 선점 시점의 observedUpdatedAt이 일치할 때만 반영하여 이전 실행의 덮어쓰기를 방지한다.
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 선점/실행 시작 시점의 updatedAt
     * @param commitSha 커밋 SHA
     * @param now 갱신 시각
     * @return 반영된 행 수 (1이면 성공, 0이면 이미 상태가 변경됨)
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'SUCCEEDED',
                commit_sha = :commitSha,
                next_retry_at = NULL,
                error_message = NULL,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int updateStatusToSucceeded(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("commitSha") String commitSha,
        @Param("now") LocalDateTime now
    );

    /**
     * 실행 중(RUNNING) 상태인 작업에 대해 조건부로 Rate Limit 상태 및 해제 시각을 반영하고 재시도 횟수를 증가시킨다 (scheduler.md > 8.1).
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 선점/실행 시작 시점의 updatedAt
     * @param resetAt Rate Limit 해제 시각
     * @param errorMessage 오류 메시지
     * @param now 갱신 시각
     * @return 반영된 행 수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'RATE_LIMITED',
                retry_count = retry_count + 1,
                next_retry_at = :resetAt,
                error_message = :errorMessage,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int updateStatusToRateLimited(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("resetAt") LocalDateTime resetAt,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );

    /**
     * 실행 중(RUNNING) 상태인 작업에 대해 조건부로 재시도 횟수를 증가시키고 다음 재시도 시각을 반영한다 (scheduler.md > 8.1).
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 선점/실행 시작 시점의 updatedAt
     * @param nextRetryAt 다음 재시도 시각
     * @param errorMessage 오류 메시지
     * @param now 갱신 시각
     * @return 반영된 행 수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'RETRY_WAITING',
                retry_count = retry_count + 1,
                next_retry_at = :nextRetryAt,
                error_message = :errorMessage,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int updateStatusToRetryWaiting(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("nextRetryAt") LocalDateTime nextRetryAt,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );

    /**
     * 실행 중(RUNNING) 상태인 작업에 대해 조건부로 최종 실패(FAILED) 상태를 반영한다 (scheduler.md > 8.1).
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 선점/실행 시작 시점의 updatedAt
     * @param errorMessage 오류 메시지
     * @param now 갱신 시각
     * @return 반영된 행 수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'FAILED',
                next_retry_at = NULL,
                error_message = :errorMessage,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int updateStatusToFailed(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );

    /**
     * 실행 중(RUNNING) 상태인 작업을 조건부로 취소(CANCELED) 상태로 반영한다.
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 선점/실행 시작 시점의 updatedAt
     * @param reason 취소 사유
     * @param now 갱신 시각
     * @return 반영된 행 수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'CANCELED',
                next_retry_at = NULL,
                error_message = :reason,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int updateStatusToCanceled(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("reason") String reason,
        @Param("now") LocalDateTime now
    );

    /**
     * 실패(FAILED) 또는 취소(CANCELED) 상태인 작업을 조건부로 대기(PENDING) 상태로 초기화한다 (api-spec.md > 8.1.7, scheduler.md > 8.1).
     *
     * @param id 작업 ID
     * @param connectionId 현재 활성 연동 ID
     * @param now 갱신 시각
     * @return 반영된 행 수 (1이면 초기화 성공, 0이면 이미 다른 상태로 변경됨)
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET connection_id = :connectionId,
                status = 'PENDING',
                retry_count = 0,
                next_retry_at = NULL,
                error_message = NULL,
                updated_at = :now
            WHERE id = :id AND status IN ('FAILED', 'CANCELED')
            """,
        nativeQuery = true
    )
    int resetJobToPendingIfRetryable(
        @Param("id") Long id,
        @Param("connectionId") Long connectionId,
        @Param("now") LocalDateTime now
    );

    /**
     * 실행 대기(PENDING, RETRY_WAITING, RATE_LIMITED) 상태의 다음 1개 작업을 선점하기 위해
     * SKIP LOCKED로 조회한다 (scheduler.md > 8.1).
     *
     * @param now 현재 시각
     * @return 선점된 GithubSyncJob Optional
     */
    @Query(
        value = """
            SELECT * FROM github_sync_jobs
            WHERE status IN ('PENDING', 'RETRY_WAITING', 'RATE_LIMITED')
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
        nativeQuery = true
    )
    Optional<GithubSyncJob> findNextClaimableJobForUpdateSkipLocked(@Param("now") LocalDateTime now);

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
     * 실행 중(RUNNING) 상태에서 임계 시각을 초과한 정체 작업 목록을 연동 정보와 함께 Fetch Join 조회한다 (scheduler.md > 8.2).
     *
     * @param status 작업 상태 (RUNNING)
     * @param cutoff 기준 시각
     * @return 정체된 GithubSyncJob 목록
     */
    @Query("SELECT j FROM GithubSyncJob j JOIN FETCH j.connection WHERE j.status = :status AND j.updatedAt < :cutoff")
    List<GithubSyncJob> findByStatusAndUpdatedAtBeforeWithConnection(
        @Param("status") GithubSyncJobStatus status,
        @Param("cutoff") LocalDateTime cutoff
    );

    /**
     * 정체된 작업을 조건부(observedUpdatedAt 일치)로 RETRY_WAITING으로 복구한다 (scheduler.md > 8.2).
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 조회 시점의 updatedAt
     * @param nextRetryAt 다음 재시도 시각
     * @param errorMessage 오류 메시지
     * @param now 갱신 시각
     * @return 반영된 행 수 (1이면 성공, 0이면 경합으로 다른 곳에서 이미 갱신됨)
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'RETRY_WAITING',
                retry_count = retry_count + 1,
                next_retry_at = :nextRetryAt,
                error_message = :errorMessage,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int recoverStuckJobToRetryWaiting(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("nextRetryAt") LocalDateTime nextRetryAt,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );

    /**
     * 정체된 작업을 조건부(observedUpdatedAt 일치)로 FAILED로 복구/종결한다 (scheduler.md > 8.2).
     *
     * @param id 작업 ID
     * @param observedUpdatedAt 조회 시점의 updatedAt
     * @param errorMessage 오류 메시지
     * @param now 갱신 시각
     * @return 반영된 행 수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query(
        value = """
            UPDATE github_sync_jobs
            SET status = 'FAILED',
                next_retry_at = NULL,
                error_message = :errorMessage,
                updated_at = :now
            WHERE id = :id AND status = 'RUNNING' AND updated_at = :observedUpdatedAt
            """,
        nativeQuery = true
    )
    int recoverStuckJobToFailed(
        @Param("id") Long id,
        @Param("observedUpdatedAt") LocalDateTime observedUpdatedAt,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );
}

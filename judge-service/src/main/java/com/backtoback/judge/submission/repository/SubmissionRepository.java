package com.backtoback.judge.submission.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.judge.submission.domain.Submission;

import jakarta.persistence.LockModeType;

/** {@code Submission} 저장과 식별자 조회를 제공한다. */
public interface SubmissionRepository extends JpaRepository<Submission, Long>, JpaSpecificationExecutor<Submission> {
    Optional<Submission> findByIdAndMemberIdAndContext(long id, long memberId, Submission.Context context);

    /**
     * 회원에게 현재 연결된 멱등 키의 제출을 조회한다.
     *
     * @param memberId 인증 회원 ID
     * @param idempotencyKey 멱등 키
     * @return 기존 제출
     */
    Optional<Submission> findByMemberIdAndIdempotencyKey(long memberId, String idempotencyKey);

    /**
     * 만료된 원본 행의 키만 해제하며 코드·해시·결과는 보존한다.
     *
     * @param id 이전 제출 ID
     * @param key 해제할 원래 키
     * @param cutoff 만료 경계 시각
     * @return 해제한 행 수
     */
    @Modifying
    @Query(
        value = """
            UPDATE submissions SET idempotency_key = NULL
            WHERE id = :id AND idempotency_key = :key AND created_at <= :cutoff
            """,
        nativeQuery = true
    )
    int releaseExpiredKey(@Param("id") long id, @Param("key") String key, @Param("cutoff") LocalDateTime cutoff);

    /**
     * 관리 엔티티 캐시를 거치지 않고 제출 행을 잠가 최신 실행 환경 참조를 읽는다.
     *
     * @param id 제출 ID
     * @return 존재하면 잠근 제출의 최신 참조
     */
    @Query(
        value = "SELECT id, runtime_id FROM submissions WHERE id = :id FOR UPDATE",
        nativeQuery = true
    )
    Optional<RuntimeReference> findRuntimeForUpdate(@Param("id") long id);

    /**
     * 상위 트랜잭션의 이전 스냅샷 이후 생성된 제출도 현재 읽기로 조회한다.
     * 이미 관리 중인 엔티티는 그대로 반환하므로 최신 실행 환경 참조는 별도로 동기화해야 한다.
     *
     * @param id 제출 ID
     * @return 존재하면 잠근 제출 엔티티
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select submission from Submission submission where submission.id = :id")
    Optional<Submission> findCurrentForUpdate(@Param("id") long id);

    /**
     * 잠금 조회의 스칼라 결과이며 기존 관리 엔티티의 상태를 재사용하지 않는다.
     *
     * @param submissionId 잠근 제출 ID
     * @param runtimeId 확정한 실행 환경 ID; 미확정이면 {@code null}
     */
    record RuntimeReference(long submissionId, Long runtimeId) {
    }
}

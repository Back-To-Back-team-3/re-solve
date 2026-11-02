package com.backtoback.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.problem.problem.domain.ProblemRevision;

import jakarta.persistence.LockModeType;

/** 문제 수정 회차를 저장하고, 문제·회차 번호로 조회한다. */
public interface ProblemRevisionRepository extends JpaRepository<ProblemRevision, Long> {
    /** 작성자 멱등키로 기존 회차 등록 결과를 조회한다. */
    Optional<ProblemRevision> findByAuthorIdAndIdempotencyKey(Long authorId, String idempotencyKey);

    /** 문제 잠금 이후 최신 멱등 기록을 다시 확인한다(ERD 4.2.2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProblemRevision r where r.authorId = :authorId and r.idempotencyKey = :key")
    Optional<ProblemRevision> findByAuthorIdAndIdempotencyKeyForUpdate(
        @Param("authorId") Long authorId,
        @Param("key") String key
    );

    /** 문제 행 잠금 아래 다음 회차 번호를 계산하기 위해 가장 최근 회차를 조회한다(ERD 6.2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProblemRevision> findFirstByProblemIdOrderByRevisionNumberDesc(Long problemId);

    /**
     * 문제와 회차 번호가 일치하는 수정 회차를 조회한다.
     *
     * @param problemId 소유 문제 ID
     * @param revisionNumber 문제별 회차 번호
     * @return 조건에 맞는 수정 회차
     */
    Optional<ProblemRevision> findByProblemIdAndRevisionNumber(Long problemId, int revisionNumber);
}

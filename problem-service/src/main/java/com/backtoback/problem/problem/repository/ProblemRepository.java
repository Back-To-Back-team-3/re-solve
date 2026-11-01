package com.backtoback.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemVisibility;

import jakarta.persistence.LockModeType;

/** 문제를 저장하고, 용도·공개 상태별 목록을 조회한다. */
public interface ProblemRepository extends JpaRepository<Problem, Long> {
    /**
     * 문제 행 잠금으로 회차 번호를 계산·저장하는 트랜잭션을 직렬화한다(ERD 6.2).
     *
     * @param problemId 잠글 문제 ID
     * @return 잠근 문제
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Problem p where p.id = :problemId")
    Optional<Problem> findByIdForUpdate(@Param("problemId") Long problemId);

    /**
     * 용도·공개 상태가 일치하는 문제를 페이지 조회한다.
     *
     * @param scope 용도 구분
     * @param visibility 공개 상태
     * @param pageable 페이지 범위와 정렬 조건
     * @return 조건에 맞는 문제 페이지
     */
    Page<Problem> findByScopeAndVisibility(ProblemScope scope, ProblemVisibility visibility, Pageable pageable);
}

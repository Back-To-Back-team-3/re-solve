package com.backtoback.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemRevision;

/** 문제 수정 회차를 저장하고, 문제·회차 번호로 조회한다. */
public interface ProblemRevisionRepository extends JpaRepository<ProblemRevision, Long> {
    /**
     * 문제와 회차 번호가 일치하는 수정 회차를 조회한다.
     *
     * @param problemId 소유 문제 ID
     * @param revisionNumber 문제별 회차 번호
     * @return 조건에 맞는 수정 회차
     */
    Optional<ProblemRevision> findByProblemIdAndRevisionNumber(Long problemId, int revisionNumber);
}

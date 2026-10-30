package com.backtoback.problem.problem.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemRevisionTestEntry;

/** 회차별 테스트 구성을 저장하고, 회차에 속한 테스트를 순서대로 조회한다. */
public interface ProblemRevisionTestEntryRepository extends JpaRepository<ProblemRevisionTestEntry, Long> {
    /**
     * 문제 수정 회차에 속한 테스트 구성을 표시 순서대로 조회한다.
     *
     * @param problemRevisionId 문제 수정 회차 ID
     * @return 순서대로 정렬된 테스트 구성 목록
     */
    List<ProblemRevisionTestEntry> findByProblemRevisionIdOrderByTestOrder(Long problemRevisionId);
}

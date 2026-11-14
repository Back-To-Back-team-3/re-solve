package com.backtoback.problem.problem.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemRevisionTag;

/** 문제 수정 회차와 태그의 불변 연결을 저장하고 조회한다. */
public interface ProblemRevisionTagRepository extends JpaRepository<ProblemRevisionTag, Long> {
    /**
     * 회차 ID가 일치하는 태그 연결을 태그 ID 순서로 조회한다.
     *
     * @param problemRevisionId 조회할 문제 회차 ID
     * @return 회차에 연결된 태그 목록
     */
    List<ProblemRevisionTag> findByProblemRevisionIdOrderByTagId(Long problemRevisionId);
}

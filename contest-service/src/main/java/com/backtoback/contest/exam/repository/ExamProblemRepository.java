package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.ExamProblem;

/**
 * 시험에 고정된 문제를 저장하고 조회한다.
 */
public interface ExamProblemRepository extends JpaRepository<ExamProblem, Long> {

}

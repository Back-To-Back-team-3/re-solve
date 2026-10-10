package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.ExamProblem;

public interface ExamProblemRepository extends JpaRepository<ExamProblem, Long> {

}

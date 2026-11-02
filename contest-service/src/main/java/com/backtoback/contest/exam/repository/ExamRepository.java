package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.Exam;

/**
 * 시험을 저장하고 조회한다.
 */
public interface ExamRepository extends JpaRepository<Exam, Long> {

}

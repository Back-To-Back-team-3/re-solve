package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.contest.exam.domain.Exam;

public interface ExamRepository extends JpaRepository<Exam, Long> {

}

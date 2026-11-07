package com.backtoback.contest.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.contest.exam.domain.ExamProblem;

/**
 * 시험에 고정된 문제를 저장하고 조회한다.
 */
public interface ExamProblemRepository extends JpaRepository<ExamProblem, Long> {
    /** 기존 문제 행을 먼저 삭제해 같은 문제의 유일 키가 충돌하지 않게 한다. */
    @Modifying
    @Query("delete from ExamProblem problem where problem.exam.id = :examId")
    void deleteForExam(@Param("examId") Long examId);
}

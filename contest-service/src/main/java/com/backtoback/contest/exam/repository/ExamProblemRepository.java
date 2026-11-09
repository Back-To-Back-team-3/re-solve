package com.backtoback.contest.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.contest.exam.domain.ExamProblem;

/**
 * 시험에 고정된 문제를 저장하고 조회한다.
 */
public interface ExamProblemRepository extends JpaRepository<ExamProblem, Long> {
    /** 시험의 문제 수를 조회한다. */
    long countByExamId(Long examId);

    @Query("""
        select p.exam.id as examId, count(p) as problemCount
        from ExamProblem p where p.exam.id in :examIds group by p.exam.id
        """)
    List<ProblemCount> countByExamIds(@Param("examIds") List<Long> examIds);

    interface ProblemCount {
        Long getExamId();

        long getProblemCount();
    }

    /** 기존 문제 행을 먼저 삭제해 같은 문제의 유일 키가 충돌하지 않게 한다. */
    @Modifying
    @Query("delete from ExamProblem problem where problem.exam.id = :examId")
    void deleteForExam(@Param("examId") Long examId);
}

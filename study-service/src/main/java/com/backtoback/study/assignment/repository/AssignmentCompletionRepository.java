package com.backtoback.study.assignment.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentCompletion;

public interface AssignmentCompletionRepository extends JpaRepository<AssignmentCompletion, Long> {

    /**
     * 이미 있으면 무시한다. status는 항상 COMPLETED로 고정된다(ERD §6.5 uk_assignment_completions_assignment_member_problem).
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT IGNORE INTO assignment_completions "
            + "(assignment_id, member_id, problem_id, status, completion_type, "
            + "basis_submission_id, basis_submitted_at, basis_judge_attempt, completed_at, created_at, updated_at) "
            + "VALUES (:assignmentId, :memberId, :problemId, 'COMPLETED', :completionType, "
            + ":basisSubmissionId, :basisSubmittedAt, :basisJudgeAttempt, :completedAt, NOW(6), NOW(6))",
        nativeQuery = true
    )
    void insertIgnore(
        @Param("assignmentId") Long assignmentId,
        @Param("memberId") Long memberId,
        @Param("problemId") Long problemId,
        @Param("completionType") String completionType,
        @Param("basisSubmissionId") Long basisSubmissionId,
        @Param("basisSubmittedAt") LocalDateTime basisSubmittedAt,
        @Param("basisJudgeAttempt") Integer basisJudgeAttempt,
        @Param("completedAt") LocalDateTime completedAt
    );
}

package com.backtoback.study.assignment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentCompletion;

import jakarta.persistence.LockModeType;

public interface AssignmentCompletionRepository extends JpaRepository<AssignmentCompletion, Long> {

    /**
     * 한 멤버의 문제집 내 완료 기록 전체를 반환한다 (API 명세서 §5.4.3 myCompletionType).
     */
    List<AssignmentCompletion> findByAssignmentIdAndMemberId(Long assignmentId, Long memberId);

    /**
     * 문제 1개의 완료 기록을 반환한다. 재채점 재계산 시 기존 행 유무 판단에 사용한다(ERD 4.5.8 UK).
     */
    Optional<AssignmentCompletion> findByAssignmentIdAndMemberIdAndProblemId(
        Long assignmentId,
        Long memberId,
        Long problemId
    );

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

    /**
     * (assignmentId, memberId)의 완료 기록을 FOR SHARE로 조회한다.
     * REPEATABLE READ 스냅샷을 우회해 최신 커밋을 반영한다(scheduler.md §5.0).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM AssignmentCompletion c " + "WHERE c.assignmentId = :assignmentId AND c.memberId = :memberId")
    List<AssignmentCompletion> findByAssignmentIdAndMemberIdWithForShare(
        @Param("assignmentId") Long assignmentId,
        @Param("memberId") Long memberId
    );
}

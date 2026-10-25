package com.backtoback.study.assignment.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문제별 완료 인정(ERD 4.5.8 assignment_completions). 재채점으로 인정을 취소하는
 * {@code REVOKED} 전이 메서드는 해당 기능 Issue(재채점 반영)에서 추가한다.
 */
@Getter
@Entity
@Table(
    name = "assignment_completions",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_assignment_completions_assignment_member_problem",
            columnNames = {
                "assignment_id",
                "member_id",
                "problem_id"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_assignment_completions_member_problem",
            columnList = "member_id, problem_id"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssignmentCompletion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "assignment_id",
        nullable = false
    )
    private Long assignmentId;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private CompletionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "completion_type",
        length = 30
    )
    private CompletionType completionType;

    @Column(name = "basis_submission_id")
    private Long basisSubmissionId;

    @Column(name = "basis_submitted_at")
    private LocalDateTime basisSubmittedAt;

    @Column(name = "basis_judge_attempt")
    private Integer basisJudgeAttempt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private AssignmentCompletion(
        Long assignmentId,
        Long memberId,
        Long problemId,
        CompletionType completionType,
        Long basisSubmissionId,
        LocalDateTime basisSubmittedAt,
        Integer basisJudgeAttempt,
        LocalDateTime completedAt
    ) {
        this.assignmentId = assignmentId;
        this.memberId = memberId;
        this.problemId = problemId;
        this.status = CompletionStatus.COMPLETED;
        this.completionType = completionType;
        this.basisSubmissionId = basisSubmissionId;
        this.basisSubmittedAt = basisSubmittedAt;
        this.basisJudgeAttempt = basisJudgeAttempt;
        this.completedAt = completedAt;
    }

    /**
     * 채점 이벤트 반영으로 문제 1개의 완료를 인정한다. 근거 제출·채점 회차는 재채점 재계산의 비교 기준이 된다(ERD 4.5.8).
     */
    public static AssignmentCompletion complete(
        Long assignmentId,
        Long memberId,
        Long problemId,
        CompletionType completionType,
        Long basisSubmissionId,
        LocalDateTime basisSubmittedAt,
        Integer basisJudgeAttempt,
        LocalDateTime completedAt
    ) {
        return new AssignmentCompletion(
            assignmentId,
            memberId,
            problemId,
            completionType,
            basisSubmissionId,
            basisSubmittedAt,
            basisJudgeAttempt,
            completedAt
        );
    }
}

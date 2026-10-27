package com.backtoback.study.assignment.domain;

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
 * 문제집에 배정된 문제(ERD 4.5.6 assignment_problems). {@code problem_tags_json}은 검색·정렬에
 * 쓰지 않는 배정 스냅샷이라 {@code String}으로 원문을 그대로 저장한다(erd.md 4.5.6).
 */
@Getter
@Entity
@Table(
    name = "assignment_problems",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_assignment_problems_assignment_problem",
            columnNames = {
                "assignment_id",
                "problem_id"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_assignment_problems_problem_status",
            columnList = "problem_id, status"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssignmentProblem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "assignment_id",
        nullable = false
    )
    private Long assignmentId;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Column(
        name = "assigned_revision_id",
        nullable = false
    )
    private Long assignedRevisionId;

    @Column(
        name = "problem_title",
        nullable = false,
        length = 200
    )
    private String problemTitle;

    @Column(
        name = "problem_difficulty",
        nullable = false
    )
    private int problemDifficulty;

    @Column(
        name = "problem_tags_json",
        nullable = false,
        columnDefinition = "json"
    )
    private String problemTagsJson;

    @Column(
        name = "display_order",
        nullable = false
    )
    private int displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private AssignmentProblemStatus status;

    private AssignmentProblem(
        Long assignmentId,
        Long problemId,
        Long assignedRevisionId,
        String problemTitle,
        int problemDifficulty,
        String problemTagsJson,
        int displayOrder
    ) {
        this.assignmentId = assignmentId;
        this.problemId = problemId;
        this.assignedRevisionId = assignedRevisionId;
        this.problemTitle = problemTitle;
        this.problemDifficulty = problemDifficulty;
        this.problemTagsJson = problemTagsJson;
        this.displayOrder = displayOrder;
        this.status = AssignmentProblemStatus.ACTIVE;
    }

    /**
     * 문제집에 문제 1개를 배정한다. 문제 제목·난이도·태그는 배정 시점 스냅샷으로 고정한다(ERD 4.5.6).
     */
    public static AssignmentProblem assign(
        Long assignmentId,
        Long problemId,
        Long assignedRevisionId,
        String problemTitle,
        int problemDifficulty,
        String problemTagsJson,
        int displayOrder
    ) {
        return new AssignmentProblem(
            assignmentId,
            problemId,
            assignedRevisionId,
            problemTitle,
            problemDifficulty,
            problemTagsJson,
            displayOrder
        );
    }
}

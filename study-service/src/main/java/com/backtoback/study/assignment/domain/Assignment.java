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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문제집 애그리게이트(ERD 4.5.5 assignments). 개설·마감 등 상태 전이 메서드는 해당 기능 Issue에서 추가한다.
 */
@Getter
@Entity
@Table(
    name = "assignments",
    indexes = {
        @Index(
            name = "idx_assignments_status_starts_at",
            columnList = "status, starts_at"
        ),
        @Index(
            name = "idx_assignments_status_deadline_at",
            columnList = "status, deadline_at"
        ),
        @Index(
            name = "idx_assignments_study_starts_at",
            columnList = "study_id, starts_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Assignment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "study_id",
        nullable = false
    )
    private Long studyId;

    @Column(
        nullable = false,
        length = 200
    )
    private String title;

    @Column(name = "tag_id")
    private Long tagId;

    @Column(length = 500)
    private String goal;

    @Column(
        name = "is_pre_solved_allowed",
        nullable = false
    )
    private boolean preSolvedAllowed;

    @Column(
        name = "starts_at",
        nullable = false
    )
    private LocalDateTime startsAt;

    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private AssignmentStatus status;

    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    private Assignment(
        Long studyId,
        String title,
        Long tagId,
        String goal,
        boolean preSolvedAllowed,
        LocalDateTime startsAt,
        LocalDateTime deadlineAt
    ) {
        this.studyId = studyId;
        this.title = title;
        this.tagId = tagId;
        this.goal = goal;
        this.preSolvedAllowed = preSolvedAllowed;
        this.startsAt = startsAt;
        this.deadlineAt = deadlineAt;
        this.status = AssignmentStatus.SCHEDULED;
    }

    /**
     * 예정 상태의 새 문제집을 만든다. `starts_at` 도달 시 `OPEN` 전이는 스케줄러가 담당한다(ERD 4.5.5 상태값).
     */
    public static Assignment schedule(
        Long studyId,
        String title,
        Long tagId,
        String goal,
        boolean preSolvedAllowed,
        LocalDateTime startsAt,
        LocalDateTime deadlineAt
    ) {
        return new Assignment(studyId, title, tagId, goal, preSolvedAllowed, startsAt, deadlineAt);
    }
}

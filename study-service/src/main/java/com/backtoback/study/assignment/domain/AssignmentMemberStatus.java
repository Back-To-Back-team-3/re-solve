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
 * 멤버별 과제 진행 상태(ERD 4.5.7 assignment_member_statuses). 완료·미완료 재계산 메서드는
 * 해당 기능 Issue(채점 이벤트 반영)에서 추가한다.
 */
@Getter
@Entity
@Table(
    name = "assignment_member_statuses",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_assignment_member_statuses_assignment_member",
            columnNames = {
                "assignment_id",
                "member_id"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_assignment_member_statuses_member",
            columnList = "member_id"
        ),
        @Index(
            name = "idx_assignment_member_statuses_assignment_status",
            columnList = "assignment_id, status"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssignmentMemberStatus extends BaseTimeEntity {

    private static final int INITIAL_COUNT = 0;

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
        name = "membership_id",
        nullable = false
    )
    private Long membershipId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private MemberProgressStatus status;

    @Column(
        name = "completed_count",
        nullable = false
    )
    private int completedCount;

    @Column(
        name = "on_time_count",
        nullable = false
    )
    private int onTimeCount;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private AssignmentMemberStatus(Long assignmentId, Long memberId, Long membershipId) {
        this.assignmentId = assignmentId;
        this.memberId = memberId;
        this.membershipId = membershipId;
        this.status = MemberProgressStatus.NOT_STARTED;
        this.completedCount = INITIAL_COUNT;
        this.onTimeCount = INITIAL_COUNT;
    }

    /**
     * 가입·문제집 개설 시점에 멤버별 과제 상태 행을 만든다(ERD 4.5.7: "가입·개설 upsert 합류점").
     */
    public static AssignmentMemberStatus start(Long assignmentId, Long memberId, Long membershipId) {
        return new AssignmentMemberStatus(assignmentId, memberId, membershipId);
    }
}

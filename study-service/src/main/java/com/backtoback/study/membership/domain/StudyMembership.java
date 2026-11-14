package com.backtoback.study.membership.domain;

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
 * 스터디 가입 애그리게이트(ERD 4.5.2 study_memberships).
 * {@code active_member_id}·{@code leader_key} 생성 컬럼은 DB 유니크 제약 전용이라 매핑하지 않는다.
 * 승인·거절·탈퇴 등 상태 전이 메서드는 해당 기능 Issue에서 추가한다.
 */
@Getter
@Entity
@Table(
    name = "study_memberships",
    indexes = {
        @Index(
            name = "idx_study_memberships_status_expires_at",
            columnList = "status, expires_at"
        ),
        @Index(
            name = "idx_study_memberships_member_status",
            columnList = "member_id, status"
        ),
        @Index(
            name = "idx_study_memberships_study_status",
            columnList = "study_id, status"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyMembership extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "study_id",
        nullable = false
    )
    private Long studyId;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private MembershipRole role;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private MembershipStatus status;

    @Column(
        name = "applied_at",
        nullable = false
    )
    private LocalDateTime appliedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(
        name = "removed_reason",
        length = 500
    )
    private String removedReason;

    private StudyMembership(
        Long studyId,
        Long memberId,
        MembershipRole role,
        MembershipStatus status,
        LocalDateTime appliedAt,
        LocalDateTime expiresAt,
        LocalDateTime joinedAt
    ) {
        this.studyId = studyId;
        this.memberId = memberId;
        this.role = role;
        this.status = status;
        this.appliedAt = appliedAt;
        this.expiresAt = expiresAt;
        this.joinedAt = joinedAt;
    }

    /**
     * 가입 신청 또는 즉시 가입 행을 만든다. 상태·만료 시각 계산(즉시 가입/승인 대기 구분)은 가입 기능에서 결정해 전달한다
     * (정책 및 상태 §7 가입 방식·신청 취소·만료).
     */
    public static StudyMembership apply(
        Long studyId,
        Long memberId,
        MembershipRole role,
        MembershipStatus status,
        LocalDateTime appliedAt,
        LocalDateTime expiresAt,
        LocalDateTime joinedAt
    ) {
        return new StudyMembership(studyId, memberId, role, status, appliedAt, expiresAt, joinedAt);
    }
}

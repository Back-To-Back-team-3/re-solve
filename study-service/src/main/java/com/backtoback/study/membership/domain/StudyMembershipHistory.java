package com.backtoback.study.membership.domain;

import java.time.LocalDateTime;

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
 * 가입 상태 이력(ERD 4.5.3 study_membership_histories, {@code [IMMUTABLE]}).
 * 생성 시각만 있는 불변 테이블이라 {@code BaseTimeEntity}를 상속하지 않고, 기록 시점에 호출자가 시각을 전달한다
 * (공통 모듈 적용 가이드 §2.3 별도 시간 처리).
 */
@Getter
@Entity
@Table(
    name = "study_membership_histories",
    indexes = {
        @Index(
            name = "idx_study_membership_histories_membership_created_at",
            columnList = "membership_id, created_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyMembershipHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "membership_id",
        nullable = false
    )
    private Long membershipId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "from_status",
        length = 30
    )
    private MembershipStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "to_status",
        nullable = false,
        length = 30
    )
    private MembershipStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "from_role",
        length = 30
    )
    private MembershipRole fromRole;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "to_role",
        nullable = false,
        length = 30
    )
    private MembershipRole toRole;

    @Column(name = "actor_member_id")
    private Long actorMemberId;

    @Column(length = 500)
    private String reason;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;

    private StudyMembershipHistory(
        Long membershipId,
        MembershipStatus fromStatus,
        MembershipStatus toStatus,
        MembershipRole fromRole,
        MembershipRole toRole,
        Long actorMemberId,
        String reason,
        LocalDateTime createdAt
    ) {
        this.membershipId = membershipId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromRole = fromRole;
        this.toRole = toRole;
        this.actorMemberId = actorMemberId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    /**
     * 가입 상태·역할 전이 1건을 기록한다. {@code fromStatus}·{@code fromRole}은 최초 생성이면 {@code null}이다
     * (ERD 4.5.3).
     */
    public static StudyMembershipHistory record(
        Long membershipId,
        MembershipStatus fromStatus,
        MembershipStatus toStatus,
        MembershipRole fromRole,
        MembershipRole toRole,
        Long actorMemberId,
        String reason,
        LocalDateTime createdAt
    ) {
        return new StudyMembershipHistory(
            membershipId,
            fromStatus,
            toStatus,
            fromRole,
            toRole,
            actorMemberId,
            reason,
            createdAt
        );
    }
}

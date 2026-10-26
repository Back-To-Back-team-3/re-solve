package com.backtoback.study.membership.domain;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원별 참여 카운터(ERD 4.5.4 study_member_quotas). 증가·감소는 가입 기능의 조건부 UPDATE가 담당하고
 * (정책 및 상태 §7 참여 상한), 이 엔티티는 회원당 1건을 보장하는 생성만 다룬다.
 */
@Getter
@Entity
@Table(
    name = "study_member_quotas",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_study_member_quotas_member",
            columnNames = "member_id"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyMemberQuota extends BaseTimeEntity {

    private static final int INITIAL_ACTIVE_COUNT = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "active_count",
        nullable = false
    )
    private int activeCount;

    private StudyMemberQuota(Long memberId) {
        this.memberId = memberId;
        this.activeCount = INITIAL_ACTIVE_COUNT;
    }

    /**
     * 회원의 첫 가입 시점에 참여 카운터 행을 만든다(ERD 4.5.4: "첫 가입 시 INSERT … ON DUPLICATE KEY UPDATE").
     */
    public static StudyMemberQuota init(Long memberId) {
        return new StudyMemberQuota(memberId);
    }
}

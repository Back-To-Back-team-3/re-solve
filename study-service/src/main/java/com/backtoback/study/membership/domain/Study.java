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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스터디 애그리게이트(ERD 4.5.1 studies). 가입·운영 상태 전이는 해당 기능 Issue에서 엔티티 메서드로 추가한다.
 */
@Getter
@Entity
@Table(
    name = "studies",
    indexes = {
        @Index(
            name = "idx_studies_status_created_at",
            columnList = "status, created_at"
        ),
        @Index(
            name = "idx_studies_status_skill_level",
            columnList = "status, skill_level"
        ),
        @Index(
            name = "idx_studies_status_last_activity_at",
            columnList = "status, last_activity_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Study extends BaseTimeEntity {

    private static final int INITIAL_MEMBER_COUNT = 0;
    private static final int INITIAL_MANAGER_COUNT = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        nullable = false,
        length = 200
    )
    private String name;

    @Column(length = 500)
    private String description;

    /** 값 집합이 아직 정책 문서에 없어(정책 및 상태 §미결정 사항) Enum 대신 String으로 받는다. */
    @Column(
        nullable = false,
        length = 30
    )
    private String goal;

    @Column(
        name = "skill_level",
        nullable = false,
        length = 30
    )
    private String skillLevel;

    @Column(
        name = "preferred_language",
        nullable = false,
        length = 30
    )
    private String preferredLanguage;

    @Column(
        name = "active_time_slot",
        nullable = false,
        length = 30
    )
    private String activeTimeSlot;

    @Column(nullable = false)
    private int capacity;

    @Column(
        name = "member_count",
        nullable = false
    )
    private int memberCount;

    @Column(
        name = "manager_count",
        nullable = false
    )
    private int managerCount;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "join_type",
        nullable = false,
        length = 30
    )
    private JoinType joinType;

    @Column(
        name = "is_auto_reopen",
        nullable = false
    )
    private boolean autoReopen;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private StudyStatus status;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    private Study(
        String name,
        String description,
        String goal,
        String skillLevel,
        String preferredLanguage,
        String activeTimeSlot,
        int capacity,
        JoinType joinType,
        boolean autoReopen
    ) {
        this.name = name;
        this.description = description;
        this.goal = goal;
        this.skillLevel = skillLevel;
        this.preferredLanguage = preferredLanguage;
        this.activeTimeSlot = activeTimeSlot;
        this.capacity = capacity;
        this.memberCount = INITIAL_MEMBER_COUNT;
        this.managerCount = INITIAL_MANAGER_COUNT;
        this.joinType = joinType;
        this.autoReopen = autoReopen;
        this.status = StudyStatus.RECRUITING;
    }

    /**
     * 모집 중 상태의 새 스터디를 만든다. 생성자의 LEADER 가입은 가입 기능 트랜잭션에서 별도로 처리한다(정책 및 상태 §7 정원 검증).
     */
    public static Study recruit(
        String name,
        String description,
        String goal,
        String skillLevel,
        String preferredLanguage,
        String activeTimeSlot,
        int capacity,
        JoinType joinType,
        boolean autoReopen
    ) {
        return new Study(
            name,
            description,
            goal,
            skillLevel,
            preferredLanguage,
            activeTimeSlot,
            capacity,
            joinType,
            autoReopen
        );
    }
}

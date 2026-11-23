package com.backtoback.study.membership.domain;

import java.time.LocalDateTime;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.jpa.entity.BaseTimeEntity;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

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
     * 모집 중 상태의 새 스터디를 만든다. 생성자의 LEADER 가입은 같은 트랜잭션에서 {@link #addMember()}로 처리한다.
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

    /**
     * 신규 활동을 만들 수 없는 종료 상태인지 확인한다.
     *
     * @return 종료 상태이면 {@code true}
     */
    public boolean isClosed() {
        return status == StudyStatus.CLOSED;
    }

    /**
     * 새 문제집에 배정할 문제의 난이도가 스터디 수준 상한을 충족하는지 검증한다.
     * 기존 배정은 재검증하지 않으며 개인 태그 레벨과 무관하게 판단한다.
     *
     * @param difficulty 배정 회차의 문제 난이도
     * @throws StudyException 수준이 {@code LV1}~{@code LV3}이 아니면 {@code COMMON_INVALID_REQUEST},
     *     난이도가 허용 범위를 벗어나면 {@code STUDY_ASSIGNMENT_DIFFICULTY_EXCEEDED}
     */
    public void validateAssignmentDifficulty(int difficulty) {
        int maximum = switch (skillLevel == null ? "" : skillLevel) {
            case "LV1" -> 1;
            case "LV2" -> 2;
            case "LV3" -> 3;
            default -> throw new StudyException(CommonErrorCode.COMMON_INVALID_REQUEST);
        };
        if (difficulty < 1 || difficulty > maximum) {
            throw new StudyException(StudyErrorCode.STUDY_ASSIGNMENT_DIFFICULTY_EXCEEDED);
        }
    }

    /** 가입이 APPROVED가 된 회원을 memberCount에 반영한다(정책 및 상태 §7 정원 검증). */
    public void addMember() {
        this.memberCount++;
    }

    /** 게시글 작성 등 새 활동이 생긴 시각을 콜드 스타트 정렬 기준에 남긴다(ERD 4.5.1 {@code last_activity_at}). */
    public void recordActivity(LocalDateTime occurredAt) {
        this.lastActivityAt = occurredAt;
    }
}

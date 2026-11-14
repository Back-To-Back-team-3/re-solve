package com.backtoback.study.replica.domain;

import java.time.LocalDateTime;

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
 * 학습 프로필 복제본(ERD 4.5.10 member_learning_profile_replicas). {@code LearningProfileUpdated}
 * 이벤트로 유지하며, 반영 조건({@code profileVersion} 비교)은 이벤트 소비 기능 Issue에서 구현한다.
 * {@code goal}과 JSON 컬럼 3종은 값 체계가 아직 미확정이라(정책 및 상태 미결정 사항) {@code String}으로 원문을 저장한다.
 */
@Getter
@Entity
@Table(
    name = "member_learning_profile_replicas",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_member_learning_profile_replicas_member",
            columnNames = "member_id"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberLearningProfileReplica extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(length = 30)
    private String goal;

    @Column(
        name = "tag_levels_json",
        columnDefinition = "json"
    )
    private String tagLevelsJson;

    @Column(
        name = "preferred_languages_json",
        columnDefinition = "json"
    )
    private String preferredLanguagesJson;

    @Column(
        name = "active_time_slots_json",
        columnDefinition = "json"
    )
    private String activeTimeSlotsJson;

    @Column(
        name = "profile_version",
        nullable = false
    )
    private Long profileVersion;

    @Column(
        name = "synced_at",
        nullable = false
    )
    private LocalDateTime syncedAt;

    private MemberLearningProfileReplica(
        Long memberId,
        String goal,
        String tagLevelsJson,
        String preferredLanguagesJson,
        String activeTimeSlotsJson,
        Long profileVersion,
        LocalDateTime syncedAt
    ) {
        this.memberId = memberId;
        this.goal = goal;
        this.tagLevelsJson = tagLevelsJson;
        this.preferredLanguagesJson = preferredLanguagesJson;
        this.activeTimeSlotsJson = activeTimeSlotsJson;
        this.profileVersion = profileVersion;
        this.syncedAt = syncedAt;
    }

    /**
     * 회원 1명의 학습 프로필 복제본을 만든다. 지연 이벤트 덮어쓰기 방지 비교는 이벤트 소비 기능에서 처리한다(ERD 4.5.10).
     */
    public static MemberLearningProfileReplica sync(
        Long memberId,
        String goal,
        String tagLevelsJson,
        String preferredLanguagesJson,
        String activeTimeSlotsJson,
        Long profileVersion,
        LocalDateTime syncedAt
    ) {
        return new MemberLearningProfileReplica(
            memberId,
            goal,
            tagLevelsJson,
            preferredLanguagesJson,
            activeTimeSlotsJson,
            profileVersion,
            syncedAt
        );
    }
}

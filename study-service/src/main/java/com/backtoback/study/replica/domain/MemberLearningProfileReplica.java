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
 * 학습 프로필 복제본(ERD 4.5.10 member_learning_profile_replicas). {@code LearningProfileUpdated} 이벤트로 유지하며,
 * 반영과 {@code profileVersion} 비교는 저장소의 조건부 교체 쿼리가 맡는다.
 * {@code tagLevelsJson}은 {@code {tagId: level}} 맵, 나머지 JSON 컬럼은 값 배열이다. 값 목록은 member가 소유해
 * study는 검사하지 않고 원문 {@code String}으로 저장한다(event-contract.md §M4).
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
     * 회원 1명의 학습 프로필 복제본을 만든다. 순서 값 비교를 하지 않으므로 이벤트 반영에는 저장소의 조건부 교체 쿼리를 쓴다(ERD 4.5.10).
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

    /**
     * 학습 프로필 입력(목표·선호 언어·활동 시간대 중 하나 이상)이 있는지 판단한다. 태그 레벨만 있는 상태는 입력으로 보지 않는다
     * (정책 및 상태 8.2 스터디 추천 > 이용 조건). 빈 목록은 {@code null}로 저장된다(ERD 4.5.10).
     */
    public boolean hasProfileInput() {
        return goal != null || preferredLanguagesJson != null || activeTimeSlotsJson != null;
    }
}

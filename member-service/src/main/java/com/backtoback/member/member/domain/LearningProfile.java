package com.backtoback.member.member.domain;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 학습 프로필 (ERD 4.1.2). 회원당 1건이다. 태그에 먼저 진입하면 프로필 항목 없이 순번만 가진 행으로 시작한다.
 */
@Getter
@Entity
@Table(name = "learning_profiles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private LearningGoal goal;

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

    @Column(nullable = false)
    private Long profileVersion;

    private LearningProfile(Long memberId) {
        this.memberId = memberId;
        this.profileVersion = 0L;
    }

    /**
     * 아직 저장하지 않은 빈 프로필. 첫 변경({@link #change}·{@link #tagLevelsChanged})에서 순번 1이 된다.
     */
    public static LearningProfile empty(Long memberId) {
        return new LearningProfile(memberId);
    }

    /**
     * 사용자가 프로필 항목을 입력했는지 여부. 태그 진입만 한 행은 {@code false}다.
     */
    public boolean isEntered() {
        return goal != null;
    }

    /**
     * 프로필 항목을 바꾸고 순번을 올린다 (API 명세서 1.3.2). 값이 같은지는 호출하는 쪽이 확인한다.
     */
    public void change(LearningGoal goal, String preferredLanguagesJson, String activeTimeSlotsJson) {
        this.goal = goal;
        this.preferredLanguagesJson = preferredLanguagesJson;
        this.activeTimeSlotsJson = activeTimeSlotsJson;
        this.profileVersion++;
    }

    /**
     * 태그 레벨이 바뀌어 스냅샷이 달라졌으므로 순번을 올린다 (이벤트 계약 M4 ③④).
     */
    public void tagLevelsChanged() {
        this.profileVersion++;
    }
}

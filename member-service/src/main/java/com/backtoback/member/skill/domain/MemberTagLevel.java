package com.backtoback.member.skill.domain;

import java.time.LocalDateTime;

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
 * 회원의 태그별 레벨 (ERD 4.1.3). 회원·태그당 1건이다.
 */
@Getter
@Entity
@Table(name = "member_tag_levels")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTagLevel extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long tagId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private TagLevel level;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TagLevel diagnosedLevel;

    private LocalDateTime lastDiagnosisEndedAt;

    @Column(nullable = false)
    private LocalDateTime enteredAt;

    private MemberTagLevel(Long memberId, Long tagId, TagLevel level, LocalDateTime enteredAt) {
        this.memberId = memberId;
        this.tagId = tagId;
        this.level = level;
        this.enteredAt = enteredAt;
    }

    /**
     * 진단 없이 태그에 진입한다 (API 명세서 1.4.2). {@code LV1}로 시작하고 쿨타임을 걸지 않는다.
     */
    public static MemberTagLevel enterWithoutDiagnosis(Long memberId, Long tagId, LocalDateTime enteredAt) {
        return new MemberTagLevel(memberId, tagId, TagLevel.LV1, enteredAt);
    }

    /**
     * 진단 결과를 반영한다 (정책 및 상태 8.3). 재응시로 레벨을 내리지 않고({@code max(현재, 결과)}), 진단 최고 결과를 남긴다.
     * 채점 장애로 쿨타임이 면제되면 종료 시각을 지워 바로 다시 볼 수 있게 한다.
     *
     * @return 반영 후 레벨
     */
    public TagLevel applyDiagnosis(TagLevel result, boolean cooldownExempt) {
        if (result.ordinal() > level.ordinal()) {
            this.level = result;
        }
        if (diagnosedLevel == null || result.ordinal() > diagnosedLevel.ordinal()) {
            this.diagnosedLevel = result;
        }
        if (cooldownExempt) {
            this.lastDiagnosisEndedAt = null;
        }
        return level;
    }

    /**
     * 진단이 끝난 시각을 기록해 재응시 쿨타임을 시작한다 (정책 및 상태 8.3).
     */
    public void diagnosisEnded(LocalDateTime endedAt) {
        this.lastDiagnosisEndedAt = endedAt;
    }

    /**
     * 진단으로 태그에 처음 진입한다 (API 명세서 1.5.1). 레벨은 {@code LV0}으로 두고 진단 결과로 확정한다.
     */
    public static MemberTagLevel enterForDiagnosis(Long memberId, Long tagId, LocalDateTime enteredAt) {
        return new MemberTagLevel(memberId, tagId, TagLevel.LV0, enteredAt);
    }
}

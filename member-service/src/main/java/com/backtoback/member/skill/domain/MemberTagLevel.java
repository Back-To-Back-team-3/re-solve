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
}

package com.backtoback.member.skill.domain;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * 레벨 변경 이력 (ERD 4.1.5). 수정하지 않는 불변 테이블이라 {@code BaseTimeEntity} 대신 생성 시각만 둔다(공통 모듈 적용 가이드 2.3).
 */
@Getter
@Entity
@Table(name = "member_level_histories")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberLevelHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        nullable = false,
        updatable = false
    )
    private Long memberTagLevelId;

    @Enumerated(EnumType.STRING)
    @Column(
        length = 30,
        updatable = false
    )
    private TagLevel fromLevel;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        updatable = false
    )
    private TagLevel toLevel;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "reason_type",
        nullable = false,
        length = 30,
        updatable = false
    )
    private LevelChangeReason reason;

    @Column(updatable = false)
    private Long diagnosisAttemptId;

    @Column(updatable = false)
    private Long sourceSubmissionId;

    @CreatedDate
    @Column(
        nullable = false,
        updatable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime createdAt;

    private MemberLevelHistory(Long memberTagLevelId, TagLevel fromLevel, TagLevel toLevel, LevelChangeReason reason) {
        this.memberTagLevelId = memberTagLevelId;
        this.fromLevel = fromLevel;
        this.toLevel = toLevel;
        this.reason = reason;
    }

    /**
     * 진단 없이 진입한 기록. 이전 레벨이 없다.
     */
    public static MemberLevelHistory entrySkip(MemberTagLevel tagLevel) {
        return new MemberLevelHistory(tagLevel.getId(), null, tagLevel.getLevel(), LevelChangeReason.ENTRY_SKIP);
    }
}

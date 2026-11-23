package com.backtoback.study.recommendation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원별 스터디 추천 결과(ERD 4.5.11 study_recommendations). 학습 프로필 복제본에서 다시 계산할 수 있는 파생 데이터라
 * 재계산 시 회원의 기존 행을 교체한다. {@code studyId}는 서비스 내부 FK만 두고 연관관계로 매핑하지 않는다.
 */
@Getter
@Entity
@Table(
    name = "study_recommendations",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_study_recommendations_member_study",
            columnNames = {
                "member_id",
                "study_id"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_study_recommendations_member_score",
            columnList = "member_id, score"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyRecommendation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "study_id",
        nullable = false
    )
    private Long studyId;

    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal score;

    @Column(
        name = "reasons_json",
        nullable = false,
        columnDefinition = "json"
    )
    private String reasonsJson;

    @Column(
        name = "generated_at",
        nullable = false
    )
    private LocalDateTime generatedAt;

    private StudyRecommendation(
        Long memberId,
        Long studyId,
        BigDecimal score,
        String reasonsJson,
        LocalDateTime generatedAt
    ) {
        this.memberId = memberId;
        this.studyId = studyId;
        this.score = score;
        this.reasonsJson = reasonsJson;
        this.generatedAt = generatedAt;
    }

    /**
     * 회원 1명에 대한 스터디 1건의 추천 결과를 만든다.
     *
     * @param reasonsJson 추천 근거 문자열 배열의 JSON 원문
     */
    public static StudyRecommendation create(
        Long memberId,
        Long studyId,
        BigDecimal score,
        String reasonsJson,
        LocalDateTime generatedAt
    ) {
        return new StudyRecommendation(memberId, studyId, score, reasonsJson, generatedAt);
    }
}

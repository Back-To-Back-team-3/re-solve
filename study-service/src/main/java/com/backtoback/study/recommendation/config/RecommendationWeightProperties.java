package com.backtoback.study.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 규칙 기반 스터디 추천 항목별 점수(정책 및 상태 8.2 스터디 추천, 스케줄러 명세 5.4).
 *
 * @param goal 목표 일치 점수
 * @param level 태그 레벨 차이가 0·1일 때 점수
 * @param levelFar 태그 레벨 차이가 2일 때 점수
 * @param language 선호 언어 일치 점수
 * @param timeSlot 활동 시간대 겹침 점수
 */
@Validated
@ConfigurationProperties(prefix = "study.recommendation.weight")
public record RecommendationWeightProperties(
    @PositiveOrZero int goal,
    @PositiveOrZero int level,
    @PositiveOrZero int levelFar,
    @PositiveOrZero int language,
    @PositiveOrZero int timeSlot
) {

    private static final int MAX_SCORE = 100;

    /**
     * 모든 항목이 일치한 점수가 추천 점수 범위(0~100)의 상한과 같아야 한다(ERD 4.5.11 {@code score}).
     *
     * @return 목표·레벨·언어·활동 시간대 점수 합이 100이면 {@code true}
     */
    @AssertTrue(message = "추천 가중치(목표·태그 레벨·선호 언어·활동 시간대)의 합은 100이어야 합니다.")
    public boolean isTotalMaxScore() {
        return goal + level + language + timeSlot == MAX_SCORE;
    }

    /**
     * 레벨 차이가 클수록 점수가 커지지 않게 한다.
     *
     * @return 레벨 차이 2 점수가 레벨 차이 0·1 점수 이하이면 {@code true}
     */
    @AssertTrue(message = "태그 레벨 차이 2 점수는 차이 0·1 점수보다 클 수 없습니다.")
    public boolean isLevelFarWithinLevel() {
        return levelFar <= level;
    }
}

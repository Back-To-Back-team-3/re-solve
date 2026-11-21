package com.backtoback.study.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import com.backtoback.study.recommendation.config.RecommendationWeightProperties;
import com.backtoback.study.recommendation.domain.SkillLevelRank;
import com.backtoback.study.recommendation.repository.RecommendationCandidateRow;

import lombok.RequiredArgsConstructor;

/**
 * 회원 학습 프로필과 스터디 속성을 비교해 규칙 기반 추천 점수와 근거를 계산한다(정책 및 상태 8.2 스터디 추천).
 * 추천 대상 선별·저장은 하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class StudyRecommendationScorer {

    static final String REASON_GOAL = "목표 일치";
    static final String REASON_LEVEL = "태그 레벨 비슷함";
    static final String REASON_LANGUAGE = "선호 언어 같음";
    static final String REASON_TIME_SLOT = "활동 시간대 겹침";

    private static final int SIMILAR_LEVEL_GAP = 1;

    private final RecommendationWeightProperties weights;

    /**
     * 근거는 가중치가 큰 항목부터 담는다(API 명세서 5.1.4 추천 스터디 {@code reasons}).
     * 값이 없거나 목록 밖인 항목은 0점으로 처리하고 예외를 던지지 않는다.
     */
    public RecommendationScore score(RecommendationProfile profile, RecommendationCandidateRow study) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (profile.goal() != null && profile.goal().equals(study.getGoal())) {
            score += weights.goal();
            reasons.add(REASON_GOAL);
        }

        OptionalInt studyRank = SkillLevelRank.of(study.getSkillLevel());
        if (profile.levelRank() != null && studyRank.isPresent()) {
            // 두 순위가 모두 1~3이므로 차이는 0~2다. 차이 2는 점수만 주고 "비슷함" 근거는 붙이지 않는다.
            int gap = Math.abs(profile.levelRank() - studyRank.getAsInt());
            if (gap <= SIMILAR_LEVEL_GAP) {
                score += weights.level();
                reasons.add(REASON_LEVEL);
            } else {
                score += weights.levelFar();
            }
        }

        String studyLanguage = RecommendationProfile.normalizeLanguage(study.getPreferredLanguage());
        if (studyLanguage != null && profile.languages().contains(studyLanguage)) {
            score += weights.language();
            reasons.add(REASON_LANGUAGE);
        }

        if (study.getActiveTimeSlot() != null && profile.timeSlots().contains(study.getActiveTimeSlot())) {
            score += weights.timeSlot();
            reasons.add(REASON_TIME_SLOT);
        }

        return new RecommendationScore(score, List.copyOf(reasons));
    }
}

package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.backtoback.study.recommendation.config.RecommendationWeightProperties;
import com.backtoback.study.recommendation.repository.RecommendationCandidateRow;

class StudyRecommendationScorerTest {

    private final StudyRecommendationScorer scorer
        = new StudyRecommendationScorer(new RecommendationWeightProperties(40, 30, 10, 20, 10));

    @Test
    @DisplayName("모든 항목이 일치하면 100점과 가중치 순서의 근거 4개를 돌려준다")
    void scoresAllMatches() {
        var profile = new RecommendationProfile("EMPLOYMENT", 2, Set.of("java"), Set.of("EVENING", "NIGHT"));

        RecommendationScore result = scorer.score(profile, study("EMPLOYMENT", "LV2", "java", "EVENING"));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.reasons())
            .containsExactly(
                StudyRecommendationScorer.REASON_GOAL,
                StudyRecommendationScorer.REASON_LEVEL,
                StudyRecommendationScorer.REASON_LANGUAGE,
                StudyRecommendationScorer.REASON_TIME_SLOT
            );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "1, LV1, 30, true",
            "1, LV2, 30, true",
            "3, LV2, 30, true",
            "1, LV3, 10, false",
            "3, LV1, 10, false"
        }
    )
    @DisplayName("레벨 차이가 0·1이면 30점과 근거를, 2이면 근거 없이 10점을 준다")
    void scoresLevelGap(int memberRank, String studyLevel, int expectedScore, boolean hasReason) {
        var profile = new RecommendationProfile(null, memberRank, Set.of(), Set.of());

        RecommendationScore result = scorer.score(profile, study("STUDY", studyLevel, "cpp", "MORNING"));

        assertThat(result.score()).isEqualTo(expectedScore);
        assertThat(result.reasons().contains(StudyRecommendationScorer.REASON_LEVEL)).isEqualTo(hasReason);
    }

    @Test
    @DisplayName("스터디 선호 언어는 앞뒤 공백과 대소문자를 무시하고 비교한다")
    void comparesLanguageIgnoringCaseAndSpaces() {
        var profile = new RecommendationProfile(null, null, Set.of("java"), Set.of());

        RecommendationScore result = scorer.score(profile, study("STUDY", "LV1", "  Java ", "MORNING"));

        assertThat(result.score()).isEqualTo(20);
        assertThat(result.reasons()).containsExactly(StudyRecommendationScorer.REASON_LANGUAGE);
    }

    @Test
    @DisplayName("회원 값이 없거나 스터디 값이 목록 밖이면 해당 항목은 예외 없이 0점이다")
    void scoresZeroForMissingOrUnknownValues() {
        var profile = new RecommendationProfile(null, null, Set.of(), Set.of());

        RecommendationScore result = scorer.score(profile, study("UNKNOWN", "LV9", null, null));

        assertThat(result.score()).isZero();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    @DisplayName("스터디 실력 수준이 목록 밖이면 회원 레벨이 있어도 레벨 점수를 주지 않는다")
    void skipsLevelWhenStudyLevelUnknown() {
        var profile = new RecommendationProfile("CONTEST", 2, Set.of(), Set.of());

        RecommendationScore result = scorer.score(profile, study("CONTEST", "LV9", "java", "NIGHT"));

        assertThat(result.score()).isEqualTo(40);
        assertThat(result.reasons()).containsExactly(StudyRecommendationScorer.REASON_GOAL);
    }

    private RecommendationCandidateRow study(String goal, String skillLevel, String language, String timeSlot) {
        return new CandidateRow(1L, goal, skillLevel, language, timeSlot, null);
    }

    private record CandidateRow(
        Long studyId,
        String goal,
        String skillLevel,
        String preferredLanguage,
        String activeTimeSlot,
        LocalDateTime lastActivityAt
    ) implements RecommendationCandidateRow {

        @Override
        public Long getStudyId() {
            return studyId;
        }

        @Override
        public String getGoal() {
            return goal;
        }

        @Override
        public String getSkillLevel() {
            return skillLevel;
        }

        @Override
        public String getPreferredLanguage() {
            return preferredLanguage;
        }

        @Override
        public String getActiveTimeSlot() {
            return activeTimeSlot;
        }

        @Override
        public LocalDateTime getLastActivityAt() {
            return lastActivityAt;
        }
    }
}

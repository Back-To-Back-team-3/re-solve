package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.study.replica.domain.MemberLearningProfileReplica;

import tools.jackson.databind.json.JsonMapper;

class RecommendationProfileTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("태그 레벨은 정규화한 순위 중 최고값으로 모으고 언어는 공백·대소문자를 정규화한다")
    void readsReplicaColumns() {
        var replica
            = replica(
                "EMPLOYMENT",
                "{\"2\": \"LV1\", \"4\": \"MASTER\"}",
                "[\" Java \", \"python3\"]",
                "[\"EVENING\"]"
            );

        RecommendationProfile profile = RecommendationProfile.from(replica, objectMapper);

        assertThat(profile.goal()).isEqualTo("EMPLOYMENT");
        assertThat(profile.levelRank()).isEqualTo(3);
        assertThat(profile.languages()).containsExactlyInAnyOrder("java", "python3");
        assertThat(profile.timeSlots()).containsExactly("EVENING");
    }

    @Test
    @DisplayName("미입력 컬럼은 레벨 null과 빈 목록으로 읽는다")
    void readsNullColumnsAsEmpty() {
        var replica = replica(null, null, null, null);

        RecommendationProfile profile = RecommendationProfile.from(replica, objectMapper);

        assertThat(profile.goal()).isNull();
        assertThat(profile.levelRank()).isNull();
        assertThat(profile.languages()).isEmpty();
        assertThat(profile.timeSlots()).isEmpty();
    }

    @Test
    @DisplayName("목록 밖 레벨만 있으면 레벨을 null로 읽는다")
    void ignoresUnknownLevels() {
        var replica = replica("STUDY", "{\"2\": \"LV9\"}", null, null);

        RecommendationProfile profile = RecommendationProfile.from(replica, objectMapper);

        assertThat(profile.levelRank()).isNull();
    }

    @Test
    @DisplayName("읽을 수 없는 JSON 컬럼은 예외 없이 미입력으로 처리하고 나머지 컬럼은 읽는다")
    void treatsMalformedJsonAsEmpty() {
        var replica = replica("CONTEST", "[\"LV1\"]", "{broken", "[\"NIGHT\"]");

        RecommendationProfile profile = RecommendationProfile.from(replica, objectMapper);

        assertThat(profile.goal()).isEqualTo("CONTEST");
        assertThat(profile.levelRank()).isNull();
        assertThat(profile.languages()).isEmpty();
        assertThat(profile.timeSlots()).containsExactly("NIGHT");
    }

    private MemberLearningProfileReplica replica(
        String goal,
        String tagLevelsJson,
        String languagesJson,
        String timeSlotsJson
    ) {
        return MemberLearningProfileReplica
            .sync(1L, goal, tagLevelsJson, languagesJson, timeSlotsJson, 1L, LocalDateTime.now());
    }
}

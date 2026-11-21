package com.backtoback.study.replica.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MemberLearningProfileReplicaTest {

    @ParameterizedTest
    @CsvSource(
        nullValues = "NULL",
        value = {
            "EMPLOYMENT, NULL, NULL, true",
            "NULL, '[\"java\"]', NULL, true",
            "NULL, NULL, '[\"EVENING\"]', true",
            "NULL, NULL, NULL, false"
        }
    )
    @DisplayName("목표·선호 언어·활동 시간대 중 하나라도 있으면 학습 프로필 입력이 있다고 판단하고 태그 레벨만 있으면 없다고 판단한다")
    void hasProfileInput(String goal, String languagesJson, String timeSlotsJson, boolean expected) {
        var replica
            = MemberLearningProfileReplica
                .sync(1L, goal, "{\"3\": \"LV2\"}", languagesJson, timeSlotsJson, 1L, LocalDateTime.now());

        assertThat(replica.hasProfileInput()).isEqualTo(expected);
    }
}

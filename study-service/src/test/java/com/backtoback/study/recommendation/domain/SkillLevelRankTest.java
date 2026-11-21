package com.backtoback.study.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SkillLevelRankTest {

    @ParameterizedTest
    @CsvSource(
        {
            "LV0, 1",
            "LV1, 1",
            "LV2, 2",
            "LV3, 3",
            "MASTER, 3"
        }
    )
    @DisplayName("회원 레벨은 LV0을 1, MASTER를 3으로 정규화해 스터디 실력 수준 순위로 바꾼다")
    void normalizesToStudySkillRange(String level, int expectedRank) {
        assertThat(SkillLevelRank.of(level)).hasValue(expectedRank);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "lv1",
            "LV4",
            "BEGINNER"
        }
    )
    @DisplayName("목록 밖 레벨은 예외 없이 빈 값으로 돌려준다")
    void returnsEmptyForUnknownLevel(String level) {
        assertThat(SkillLevelRank.of(level)).isEmpty();
    }

    @Test
    @DisplayName("레벨이 없으면 빈 값으로 돌려준다")
    void returnsEmptyForNull() {
        assertThat(SkillLevelRank.of(null)).isEmpty();
    }
}

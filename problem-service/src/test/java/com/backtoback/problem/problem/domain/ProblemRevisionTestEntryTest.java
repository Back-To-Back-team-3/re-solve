package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemRevisionTestEntryTest {

    @Test
    @DisplayName("구성을 생성하면 전달한 회차·테스트 파일·순서·공개 여부·시각이 그대로 저장된다")
    void createRecordsEntry() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 31, 10, 0);

        ProblemRevisionTestEntry entry = ProblemRevisionTestEntry.create(1L, 2L, 1, true, createdAt);

        assertThat(entry.getProblemRevisionId()).isEqualTo(1L);
        assertThat(entry.getTestAssetId()).isEqualTo(2L);
        assertThat(entry.getTestOrder()).isEqualTo(1);
        assertThat(entry.isSample()).isTrue();
        assertThat(entry.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("숨김 테스트 구성을 생성하면 isSample이 false로 저장된다")
    void createHiddenEntry() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 31, 10, 0);

        ProblemRevisionTestEntry entry = ProblemRevisionTestEntry.create(1L, 3L, 3, false, createdAt);

        assertThat(entry.isSample()).isFalse();
    }
}

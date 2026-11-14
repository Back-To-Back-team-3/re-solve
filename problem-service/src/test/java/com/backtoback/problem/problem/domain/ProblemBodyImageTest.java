package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemBodyImageTest {

    @Test
    @DisplayName("연결을 생성하면 전달한 본문 파일·이미지·시각이 그대로 저장된다")
    void createRecordsLink() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 29, 10, 0);

        ProblemBodyImage link = ProblemBodyImage.create(1L, 2L, createdAt);

        assertThat(link.getBodyAssetId()).isEqualTo(1L);
        assertThat(link.getImageId()).isEqualTo(2L);
        assertThat(link.getCreatedAt()).isEqualTo(createdAt);
    }
}

package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemImageTest {

    @Test
    @DisplayName("이미지를 생성하면 연결 상태는 PENDING이고 문제 연결은 비어 있다")
    void createInitialState() {
        ProblemImage image = ProblemImage.create(1L, "problem/image/key", "v1", "a".repeat(64), "image/png", 2048L);

        assertThat(image.getOwnerId()).isEqualTo(1L);
        assertThat(image.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(image.getProblemId()).isNull();
        assertThat(image.getObjectKey()).isEqualTo("problem/image/key");
        assertThat(image.getObjectVersion()).isEqualTo("v1");
        assertThat(image.getSha256()).isEqualTo("a".repeat(64));
        assertThat(image.getContentType()).isEqualTo("image/png");
        assertThat(image.getSizeBytes()).isEqualTo(2048L);
    }
}

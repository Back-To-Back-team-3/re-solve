package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemTestAssetTest {

    @Test
    @DisplayName("테스트 파일을 생성하면 연결 상태는 PENDING이고 문제 연결·업로드 결과는 비어 있다")
    void createInitialState() {
        LocalDateTime uploadExpiresAt = LocalDateTime.of(2026, 10, 31, 10, 0);

        ProblemTestAsset asset
            = ProblemTestAsset.create(1L, "problem/test/key", "v1", "a".repeat(64), 1024L, uploadExpiresAt);

        assertThat(asset.getOwnerId()).isEqualTo(1L);
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getProblemId()).isNull();
        assertThat(asset.getObjectKey()).isEqualTo("problem/test/key");
        assertThat(asset.getObjectVersion()).isEqualTo("v1");
        assertThat(asset.getSha256()).isEqualTo("a".repeat(64));
        assertThat(asset.getSizeBytes()).isEqualTo(1024L);
        assertThat(asset.getUploadExpiresAt()).isEqualTo(uploadExpiresAt);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThat(asset.getFailureCode()).isNull();
    }
}

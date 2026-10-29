package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemBodyAssetTest {

    @Test
    @DisplayName("본문 파일을 생성하면 연결 상태는 PENDING이고 문제 연결·업로드 결과는 비어 있다")
    void createInitialState() {
        LocalDateTime uploadExpiresAt = LocalDateTime.of(2026, 10, 30, 10, 0);

        ProblemBodyAsset asset
            = ProblemBodyAsset.create(1L, "problem/body/key", "v1", "a".repeat(64), 1024L, uploadExpiresAt);

        assertThat(asset.getOwnerId()).isEqualTo(1L);
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getProblemId()).isNull();
        assertThat(asset.getObjectKey()).isEqualTo("problem/body/key");
        assertThat(asset.getObjectVersion()).isEqualTo("v1");
        assertThat(asset.getSha256()).isEqualTo("a".repeat(64));
        assertThat(asset.getSizeBytes()).isEqualTo(1024L);
        assertThat(asset.getUploadExpiresAt()).isEqualTo(uploadExpiresAt);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThat(asset.getFailureCode()).isNull();
    }
}

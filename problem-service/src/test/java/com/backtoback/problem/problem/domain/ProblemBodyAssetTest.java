package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    @DisplayName("본문 파일을 문제에 연결하면 문제 ID와 LINKED 상태를 기록한다")
    void linksPendingAssetToProblem() {
        ProblemBodyAsset asset
            = ProblemBodyAsset
                .create(1L, "problem/body/key", "v1", "a".repeat(64), 1024L, LocalDateTime.of(2026, 11, 1, 10, 0));

        ReflectionTestUtils.setField(asset, "uploadedAt", LocalDateTime.of(2026, 11, 1, 9, 0));
        ReflectionTestUtils.setField(asset, "uploadFinishedAt", LocalDateTime.of(2026, 11, 1, 9, 30));

        asset.linkToProblem(10L, 1L);
        asset.linkToProblem(10L, 1L);

        assertThat(asset.getProblemId()).isEqualTo(10L);
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.LINKED);
    }

    @Test
    @DisplayName("다른 관리자는 본문 파일을 문제에 연결할 수 없다")
    void rejectsDifferentOwner() {
        ProblemBodyAsset asset
            = ProblemBodyAsset
                .create(1L, "problem/body/key", "v1", "a".repeat(64), 1024L, LocalDateTime.of(2026, 11, 1, 10, 0));

        assertThatThrownBy(() -> asset.linkToProblem(10L, 2L)).isInstanceOf(IllegalStateException.class);
        assertThat(asset.getProblemId()).isNull();
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
    }

    @Test
    @DisplayName("저장 또는 쓰기 종료가 확인되지 않은 본문은 연결하지 않는다")
    void rejectsIncompleteUpload() {
        ProblemBodyAsset asset
            = ProblemBodyAsset
                .create(1L, "problem/body/key", "v1", "a".repeat(64), 1024L, LocalDateTime.of(2026, 11, 1, 10, 0));
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(asset, "uploadedAt", LocalDateTime.of(2026, 11, 1, 9, 0));
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(asset, "uploadedAt", null);
        ReflectionTestUtils.setField(asset, "uploadFinishedAt", LocalDateTime.of(2026, 11, 1, 9, 30));
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
    }

    @Test
    @DisplayName("만료 시각에 쓰기 종료가 확인된 본문은 연결하지 않는다")
    void rejectsUploadFinishedAtExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 1, 10, 0);
        ProblemBodyAsset asset = ProblemBodyAsset.create(1L, "problem/body/key", "v1", "a".repeat(64), 1024L, expiry);
        ReflectionTestUtils.setField(asset, "uploadedAt", expiry.minusMinutes(1));
        ReflectionTestUtils.setField(asset, "uploadFinishedAt", expiry);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(asset, "uploadedAt", expiry);
        ReflectionTestUtils.setField(asset, "uploadFinishedAt", expiry.minusMinutes(1));
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }
}

package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProblemImageTest {

    @Test
    @DisplayName("이미지를 생성하면 연결 상태는 PENDING이고 문제 연결·업로드 결과·멱등 응답은 비어 있다")
    void createInitialState() {
        LocalDateTime uploadExpiresAt = LocalDateTime.of(2026, 10, 30, 10, 0);

        ProblemImage image
            = ProblemImage.create(1L, "problem/image/key", "v1", "a".repeat(64), "image/png", 2048L, uploadExpiresAt);

        assertThat(image.getOwnerId()).isEqualTo(1L);
        assertThat(image.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(image.getProblemId()).isNull();
        assertThat(image.getObjectKey()).isEqualTo("problem/image/key");
        assertThat(image.getObjectVersion()).isEqualTo("v1");
        assertThat(image.getSha256()).isEqualTo("a".repeat(64));
        assertThat(image.getContentType()).isEqualTo("image/png");
        assertThat(image.getSizeBytes()).isEqualTo(2048L);
        assertThat(image.getUploadExpiresAt()).isEqualTo(uploadExpiresAt);
        assertThat(image.getUploadedAt()).isNull();
        assertThat(image.getUploadFinishedAt()).isNull();
        assertThat(image.getFailureCode()).isNull();
        assertThat(image.getIdempotencyKey()).isNull();
        assertThat(image.getIdempotencyExpiresAt()).isNull();
        assertThat(image.getResponseStatus()).isNull();
        assertThat(image.getResponseJson()).isNull();
    }

    @Test
    @DisplayName("이미지를 문제에 연결하면 문제 ID와 LINKED 상태를 기록한다")
    void linksPendingImageToProblem() {
        ProblemImage image
            = ProblemImage
                .create(
                    1L,
                    "problem/image/key",
                    "v1",
                    "a".repeat(64),
                    "image/png",
                    2048L,
                    LocalDateTime.of(2026, 11, 1, 10, 0)
                );

        markUploaded(image);
        image.linkToProblem(10L, 1L);
        image.linkToProblem(10L, 1L);

        assertThat(image.getProblemId()).isEqualTo(10L);
        assertThat(image.getStatus()).isEqualTo(ProblemAssetStatus.LINKED);
    }

    @Test
    @DisplayName("다른 문제에 연결된 이미지는 다시 연결할 수 없다")
    void rejectsDifferentProblemAfterLink() {
        ProblemImage image
            = ProblemImage
                .create(
                    1L,
                    "problem/image/key",
                    "v1",
                    "a".repeat(64),
                    "image/png",
                    2048L,
                    LocalDateTime.of(2026, 11, 1, 10, 0)
                );
        markUploaded(image);
        image.linkToProblem(10L, 1L);

        assertThatThrownBy(() -> image.linkToProblem(11L, 1L)).isInstanceOf(IllegalStateException.class);
        assertThat(image.getProblemId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("저장 또는 쓰기 종료가 확인되지 않은 이미지는 연결하지 않는다")
    void rejectsIncompleteUpload() {
        ProblemImage image
            = ProblemImage
                .create(
                    1L,
                    "problem/image/key",
                    "v1",
                    "a".repeat(64),
                    "image/png",
                    2048L,
                    LocalDateTime.of(2026, 11, 1, 10, 0)
                );
        assertThatThrownBy(() -> image.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(image, "uploadedAt", LocalDateTime.of(2026, 11, 1, 9, 0));
        assertThatThrownBy(() -> image.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(image, "uploadedAt", null);
        ReflectionTestUtils.setField(image, "uploadFinishedAt", LocalDateTime.of(2026, 11, 1, 9, 30));
        assertThatThrownBy(() -> image.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        assertThat(image.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
    }

    @Test
    @DisplayName("만료 시각에 쓰기 종료가 확인된 이미지는 연결하지 않는다")
    void rejectsUploadFinishedAtExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 1, 10, 0);
        ProblemImage image
            = ProblemImage.create(1L, "problem/image/key", "v1", "a".repeat(64), "image/png", 2048L, expiry);
        ReflectionTestUtils.setField(image, "uploadedAt", expiry.minusMinutes(1));
        ReflectionTestUtils.setField(image, "uploadFinishedAt", expiry);
        assertThatThrownBy(() -> image.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(image, "uploadedAt", expiry);
        ReflectionTestUtils.setField(image, "uploadFinishedAt", expiry.minusMinutes(1));
        assertThatThrownBy(() -> image.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    private void markUploaded(ProblemImage image) {
        ReflectionTestUtils.setField(image, "uploadedAt", LocalDateTime.of(2026, 11, 1, 9, 0));
        ReflectionTestUtils.setField(image, "uploadFinishedAt", LocalDateTime.of(2026, 11, 1, 9, 30));
    }
}

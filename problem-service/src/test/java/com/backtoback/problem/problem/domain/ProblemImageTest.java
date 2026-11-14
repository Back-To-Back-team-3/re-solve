package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProblemImageTest {

    @Test
    @DisplayName("만료된 멱등키를 해제해도 저장된 이미지와 최초 응답은 유지한다")
    void releaseExpiredKeyPreservesUpload() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 10, 0);
        ProblemImage image = ProblemImage.beginUpload(1L, "key", "a".repeat(64), "image/png", 10, "Key", start);
        image.completeUpload(start.plusHours(24), start.plusMinutes(1), "version", "{\"success\":true}");

        assertThatThrownBy(() -> image.releaseExpiredKey(start.plusHours(24).minusNanos(1)))
            .isInstanceOf(IllegalStateException.class);
        image.releaseExpiredKey(start.plusHours(24));
        assertThat(image.getIdempotencyKey()).isNull();
        assertThat(image.getUploadedAt()).isEqualTo(start.plusMinutes(1));
        assertThat(image.getObjectVersion()).isEqualTo("version");
        assertThat(image.getResponseStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("결과 불명인 업로드는 종료와 최초 응답을 기록하지 않는다")
    void unknownUploadRemainsUnfinished() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 10, 0);
        ProblemImage image = ProblemImage.beginUpload(1L, "key", "a".repeat(64), "image/png", 10, "key", start);
        image.recordUnknownUpload(start.plusHours(24));
        assertThat(image.getUploadFinishedAt()).isNull();
        assertThat(image.getResponseStatus()).isNull();
        assertThat(image.getFailureCode()).isEqualTo("UPLOAD_UNCONFIRMED");
    }

    @Test
    @DisplayName("만료 전 업로드만 보호 기간을 갱신하고 이전 만료값으로 완료하지 않는다")
    void renewalRequiresCurrentUnexpiredLease() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 10, 0);
        ProblemImage image = ProblemImage.beginUpload(1L, "key", "a".repeat(64), "image/png", 10, "key", start);
        LocalDateTime expiry = start.plusHours(24);
        image.renewUpload(expiry, start.plusHours(12));
        assertThat(image.getUploadExpiresAt()).isEqualTo(start.plusHours(36));
        assertThatThrownBy(() -> image.completeUpload(expiry, start.plusHours(13), null, "{}"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> image.renewUpload(start.plusHours(36), start.plusHours(36)))
            .isInstanceOf(IllegalStateException.class);
    }

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

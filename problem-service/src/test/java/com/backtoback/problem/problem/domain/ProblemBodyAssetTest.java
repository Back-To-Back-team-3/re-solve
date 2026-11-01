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

    @Test
    @DisplayName("만료 전에는 같은 업로드만 갱신하며 기존 만료 시각은 재사용할 수 없다")
    void renewsOnlyCurrentUnfinishedUploadBeforeExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemBodyAsset asset = newAsset(expiry);
        LocalDateTime renewal = expiry.minusNanos(1000);

        asset.renewUpload(expiry, renewal);

        assertThat(asset.getUploadExpiresAt()).isEqualTo(renewal.plusHours(24));
        assertThatThrownBy(() -> asset.renewUpload(expiry, renewal)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.renewUpload(asset.getUploadExpiresAt(), asset.getUploadExpiresAt()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("만료 직전 확인한 저장과 종료는 연결할 수 있고 종료 후에는 갱신하지 않는다")
    void completesBeforeExpiryAndLinks() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemBodyAsset asset = newAsset(expiry);
        LocalDateTime finished = expiry.minusNanos(1000);

        asset.completeUpload(expiry, finished, "version-1");
        asset.linkToProblem(10L, 1L);
        asset.linkToProblem(10L, 1L);

        assertThat(asset.getUploadedAt()).isEqualTo(finished);
        assertThat(asset.getUploadFinishedAt()).isEqualTo(finished);
        assertThat(asset.getObjectVersion()).isEqualTo("version-1");
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.LINKED);
        assertThatThrownBy(() -> asset.renewUpload(expiry, finished)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(11L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("만료 정각과 이후의 완료 결과는 연결 가능하게 기록하지 않는다")
    void rejectsCompletionAtAndAfterExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemBodyAsset asset = newAsset(expiry);

        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry, "v1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry.plusSeconds(1), "v1"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("결과 불명은 종료 시각 없이 보존하며 종료 확인 없는 폐기를 거절한다")
    void preservesUnknownUploadUntilTerminationIsConfirmed() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemBodyAsset asset = newAsset(expiry);

        asset.recordUnknownUpload(expiry, "UNKNOWN");

        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThat(asset.getFailureCode()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> asset.discardFinishedUpload(expiry, null, "FAILED"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("만료 후 종료가 확인된 파일은 폐기하며 늦은 성공으로 되살리지 않는다")
    void discardsExpiredUploadAfterTermination() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemBodyAsset asset = newAsset(expiry);

        asset.discardFinishedUpload(expiry, expiry.plusSeconds(1), "EXPIRED");

        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.DISCARDED);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isEqualTo(expiry.plusSeconds(1));
        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry.minusSeconds(1), "v1"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("삭제 선점된 파일은 갱신하거나 완료하거나 연결할 수 없다")
    void rejectsDeletingAsset() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemBodyAsset asset = newAsset(expiry);
        asset.completeUpload(expiry, expiry.minusSeconds(2), "v1");
        ReflectionTestUtils.setField(asset, "status", ProblemAssetStatus.DELETING);

        assertThatThrownBy(() -> asset.renewUpload(expiry, expiry.minusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry.minusSeconds(1), "v1"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    private ProblemBodyAsset newAsset(LocalDateTime expiry) {
        return ProblemBodyAsset.create(1L, "test/object", null, "a".repeat(64), 10L, expiry);
    }
}

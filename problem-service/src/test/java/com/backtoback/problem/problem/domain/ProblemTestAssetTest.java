package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    /** 갱신 경계와 변경 전 만료값의 재사용을 함께 확인한다. */
    @Test
    @DisplayName("만료 전에는 같은 업로드만 갱신하며 기존 만료 시각은 재사용할 수 없다")
    void renewsOnlyCurrentUnfinishedUploadBeforeExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemTestAsset asset = newAsset(expiry);
        LocalDateTime renewal = expiry.minusNanos(1000);

        asset.renewUpload(expiry, renewal);

        assertThat(asset.getUploadExpiresAt()).isEqualTo(renewal.plusHours(24));
        assertThatThrownBy(() -> asset.renewUpload(expiry, renewal)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.renewUpload(asset.getUploadExpiresAt(), asset.getUploadExpiresAt()))
            .isInstanceOf(IllegalStateException.class);
    }

    /** 만료 직전 완료가 재연결에 사용되고 이후 갱신되지 않는지 확인한다. */
    @Test
    @DisplayName("만료 직전 확인한 저장과 종료는 연결할 수 있고 종료 후에는 갱신하지 않는다")
    void completesBeforeExpiryAndLinks() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemTestAsset asset = newAsset(expiry);
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

    /** 만료 경계와 그 이후에는 저장 확인이 연결 가능 상태를 만들지 않는지 확인한다. */
    @Test
    @DisplayName("만료 정각과 이후의 완료 결과는 연결 가능하게 기록하지 않는다")
    void rejectsCompletionAtAndAfterExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemTestAsset asset = newAsset(expiry);

        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry, "v1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry.plusSeconds(1), "v1"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    /** 종료 확인이 없는 결과를 폐기나 연결 없이 보존하는지 확인한다. */
    @Test
    @DisplayName("결과 불명은 종료 시각 없이 보존하며 종료 확인 없는 폐기를 거절한다")
    void preservesUnknownUploadUntilTerminationIsConfirmed() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemTestAsset asset = newAsset(expiry);

        asset.recordUnknownUpload(expiry, "UNKNOWN");

        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThat(asset.getFailureCode()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> asset.discardFinishedUpload(expiry, null, "FAILED"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    /** 종료가 확인된 만료 파일의 폐기 상태와 재사용 거절을 확인한다. */
    @Test
    @DisplayName("만료 후 종료가 확인된 파일은 폐기하며 늦은 성공으로 되살리지 않는다")
    void discardsExpiredUploadAfterTermination() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemTestAsset asset = newAsset(expiry);

        asset.discardFinishedUpload(expiry, expiry.plusSeconds(1), "EXPIRED");

        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.DISCARDED);
        assertThat(asset.getUploadedAt()).isNull();
        assertThat(asset.getUploadFinishedAt()).isEqualTo(expiry.plusSeconds(1));
        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry.minusSeconds(1), "v1"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    /** 삭제 선점 이후 업로드와 참조 생성이 불가능한지 확인한다. */
    @Test
    @DisplayName("삭제 선점된 파일은 갱신하거나 완료하거나 연결할 수 없다")
    void rejectsDeletingAsset() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 2, 10, 0);
        ProblemTestAsset asset = newAsset(expiry);
        asset.completeUpload(expiry, expiry.minusSeconds(2), "v1");
        ReflectionTestUtils.setField(asset, "status", ProblemAssetStatus.DELETING);

        assertThatThrownBy(() -> asset.renewUpload(expiry, expiry.minusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.completeUpload(expiry, expiry.minusSeconds(1), "v1"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> asset.linkToProblem(10L, 1L)).isInstanceOf(IllegalStateException.class);
    }

    /** 경계 시각을 지정한 미연결 테스트 파일을 만든다. */
    private ProblemTestAsset newAsset(LocalDateTime expiry) {
        return ProblemTestAsset.create(1L, "test/object", null, "a".repeat(64), 10L, expiry);
    }
}

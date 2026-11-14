package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.repository.ProblemImageRepository;

@ExtendWith(MockitoExtension.class)
class ProblemImageUploadTrackingTest {
    @Mock
    private ProblemImageRepository images;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final ProblemAssetContent content
        = ProblemAssetContent
            .fromBytes(
                new byte[] {
                    1,
                    2
                }
            );

    @Test
    @DisplayName("같은 키와 해시의 진행 중 요청은 새 파일을 만들지 않는다")
    void reportsInProgress() {
        ProblemImage image = image(content.sha256());
        given(images.findIdByOwnerAndKey(1L, "key")).willReturn(Optional.of(7L));
        given(images.findByIdForUpdate(7L)).willReturn(Optional.of(image));
        var tracking = new ProblemImageUploadTracking(images, clock);
        assertThatThrownBy(() -> tracking.start(1L, "key", content, "image/png"))
            .isInstanceOf(ProblemException.class)
            .extracting(error -> ((ProblemException)error).getErrorCode())
            .isEqualTo(ProblemErrorCode.PROBLEM_IMAGE_UPLOAD_IN_PROGRESS);
        verify(images, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("같은 키에 다른 해시를 보내면 충돌을 반환한다")
    void rejectsDifferentHash() {
        ProblemImage image = image("a".repeat(64));
        given(images.findIdByOwnerAndKey(1L, "key")).willReturn(Optional.of(7L));
        given(images.findByIdForUpdate(7L)).willReturn(Optional.of(image));
        var tracking = new ProblemImageUploadTracking(images, clock);
        assertThatThrownBy(() -> tracking.start(1L, "key", content, "image/png"))
            .isInstanceOf(ProblemException.class)
            .extracting(error -> ((ProblemException)error).getErrorCode())
            .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        verify(images, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("종결된 동일 요청은 보관한 최초 응답을 반환한다")
    void replaysSavedResponse() {
        ProblemImage image = image(content.sha256());
        image.completeUpload(image.getUploadExpiresAt(), LocalDateTime.of(2026, 10, 31, 13, 0), null, "{}");
        given(images.findIdByOwnerAndKey(1L, "key")).willReturn(Optional.of(7L));
        given(images.findByIdForUpdate(7L)).willReturn(Optional.of(image));
        var result = new ProblemImageUploadTracking(images, clock).start(1L, "key", content, "image/png");
        assertThat(result.responseStatus()).isEqualTo(201);
        assertThat(result.responseJson()).isEqualTo("{}");
        verify(images, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("유효기간이 지난 키는 파일을 보존하고 새 객체로 접수한다")
    void releasesExpiredKeyAndCreatesNewFile() {
        ProblemImage image
            = ProblemImage
                .beginUpload(
                    1L,
                    "old-object",
                    content.sha256(),
                    "image/png",
                    2,
                    "key",
                    LocalDateTime.of(2026, 10, 31, 0, 0)
                );
        ReflectionTestUtils.setField(image, "id", 7L);
        given(images.findIdByOwnerAndKey(1L, "key")).willReturn(Optional.of(7L));
        given(images.findByIdForUpdate(7L)).willReturn(Optional.of(image));
        given(images.saveAndFlush(any())).willAnswer(invocation -> {
            ProblemImage created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 8L);
            return created;
        });
        var result = new ProblemImageUploadTracking(images, clock).start(1L, "key", content, "image/png");
        assertThat(result.id()).isEqualTo(8L);
        assertThat(result.objectKey()).isNotEqualTo("old-object");
        assertThat(image.getIdempotencyKey()).isNull();
        assertThat(image.getObjectKey()).isEqualTo("old-object");
    }

    @Test
    @DisplayName("확정 실패한 동일 요청은 최초 오류 응답을 반환한다")
    void replaysFailure() {
        ProblemImage image = image(content.sha256());
        image
            .failFinishedUpload(
                image.getUploadExpiresAt(),
                LocalDateTime.of(2026, 10, 31, 13, 0),
                "UPLOAD_FAILED",
                "{\"error\":{}}"
            );
        given(images.findIdByOwnerAndKey(1L, "key")).willReturn(Optional.of(7L));
        given(images.findByIdForUpdate(7L)).willReturn(Optional.of(image));
        var result = new ProblemImageUploadTracking(images, clock).start(1L, "key", content, "image/png");
        assertThat(result.responseStatus()).isEqualTo(503);
        assertThat(result.responseJson()).isEqualTo("{\"error\":{}}");
        verify(images, never()).saveAndFlush(any());
    }

    private ProblemImage image(String hash) {
        ProblemImage image
            = ProblemImage
                .beginUpload(1L, "object", hash, "image/png", 2, "key", LocalDateTime.of(2026, 10, 31, 12, 0));
        ReflectionTestUtils.setField(image, "id", 7L);
        return image;
    }
}

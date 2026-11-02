package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.repository.ProblemImageRepository;

@ExtendWith(MockitoExtension.class)
class ProblemImagePreviewServiceTest {
    @Mock
    private ProblemImageRepository images;
    @Mock
    private ProblemAssetObjectReader reader;

    @Test
    @DisplayName("업로드 관리자는 확인된 원본과 실제 MIME을 조회한다")
    void readsConfirmedImage() {
        byte[] bytes
            = {
                1,
                2
            };
        ProblemAssetContent content = ProblemAssetContent.fromBytes(bytes);
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        ProblemImage image = ProblemImage.beginUpload(1L, "object", content.sha256(), "image/png", 2, "key", start);
        image.completeUpload(start.plusHours(24), start.plusMinutes(1), "version", "{}");
        given(images.findById(7L)).willReturn(Optional.of(image));
        given(reader.read("bucket", "object", "version")).willReturn(bytes);
        var preview = new ProblemImagePreviewService(images, reader).read("bucket", 1L, 7L);
        assertThat(preview.contentType()).isEqualTo("image/png");
        assertThat(preview.bytes()).containsExactly(bytes);
    }

    @Test
    @DisplayName("다른 관리자가 올린 이미지는 저장소를 조회하지 않는다")
    void rejectsOtherOwner() {
        ProblemImage image
            = ProblemImage
                .beginUpload(1L, "object", "a".repeat(64), "image/png", 2, "key", LocalDateTime.of(2026, 11, 1, 0, 0));
        given(images.findById(7L)).willReturn(Optional.of(image));
        assertThatThrownBy(() -> new ProblemImagePreviewService(images, reader).read("bucket", 2L, 7L))
            .isInstanceOf(ProblemException.class);
        verifyNoInteractions(reader);
    }
}

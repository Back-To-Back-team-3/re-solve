package com.backtoback.problem.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.service.ProblemImagePreviewService;
import com.backtoback.problem.problem.service.ProblemImageUploadService;

@ExtendWith(MockitoExtension.class)
class AdminProblemImageControllerTest {
    @Mock
    private ProblemImageUploadService uploads;
    @Mock
    private ProblemImagePreviewService previews;
    private final GatewayUser user = new GatewayUser("1", UserRole.ADMIN);

    @Test
    @DisplayName("관리자 미리보기는 실제 MIME과 캐시 차단 헤더를 반환한다")
    void previewReturnsSafeHeaders() {
        byte[] bytes
            = {
                1,
                2
            };
        given(previews.read("bucket", 1L, 7L)).willReturn(new ProblemImagePreviewService.Preview("image/png", bytes));
        var response = controller("bucket").preview(user, 7L);
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("image/png");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(bytes);
    }

    @Test
    @DisplayName("파일을 두 개 전달하면 업로드를 시작하지 않는다")
    void rejectsMultipleFiles() {
        MockMultipartFile first
            = new MockMultipartFile(
                "file",
                "a.png",
                "image/png",
                new byte[] {
                    1
                }
            );
        MockMultipartFile second
            = new MockMultipartFile(
                "file",
                "b.png",
                "image/png",
                new byte[] {
                    2
                }
            );
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(first);
        request.addFile(second);
        assertThatThrownBy(() -> controller("bucket").upload(user, "key", first, request))
            .isInstanceOf(ProblemException.class);
        verifyNoInteractions(uploads);
    }

    @Test
    @DisplayName("버킷 설정이 없으면 외부 저장을 시도하지 않는다")
    void rejectsMissingBucket() {
        assertThatThrownBy(() -> controller("").preview(user, 7L)).isInstanceOf(ProblemException.class);
        verifyNoInteractions(previews);
    }

    private AdminProblemImageController controller(String bucket) {
        return new AdminProblemImageController(uploads, previews, new ProblemImageStorageProperties(bucket));
    }
}

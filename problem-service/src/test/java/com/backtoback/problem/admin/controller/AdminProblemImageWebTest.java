package com.backtoback.problem.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.service.ProblemImagePreviewService;
import com.backtoback.problem.problem.service.ProblemImageUploadService;

@WebMvcTest(AdminProblemImageController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class AdminProblemImageWebTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemImageUploadService uploads;
    @MockitoBean
    private ProblemImagePreviewService previews;
    @MockitoBean
    private ProblemImageStorageProperties storage;

    @Test
    @DisplayName("관리자가 업로드하면 저장된 최초 응답을 JSON으로 반환한다")
    void uploadsImage() throws Exception {
        given(storage.bucket()).willReturn("bucket");
        given(uploads.upload(eq("bucket"), eq(1L), eq("key"), any()))
            .willReturn(new ProblemImageUploadService.Result(201, "{\"success\":true,\"data\":{\"imageId\":\"7\"}}"));
        mvc
            .perform(
                multipart("/api/v1/admin/problems/images")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "image.png",
                            "image/png",
                            new byte[] {
                                1
                            }
                        )
                    )
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.imageId").value("7"));
    }

    @Test
    @DisplayName("필수 멱등키가 없으면 접수하지 않는다")
    void rejectsMissingKey() throws Exception {
        mvc
            .perform(
                multipart("/api/v1/admin/problems/images")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "image.png",
                            "image/png",
                            new byte[] {
                                1
                            }
                        )
                    )
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verifyNoInteractions(uploads);
    }

    @Test
    @DisplayName("관리자가 아닌 사용자는 이미지 미리보기에 접근할 수 없다")
    void rejectsNonAdmin() throws Exception {
        mvc
            .perform(
                get("/api/v1/admin/problems/images/7/content").header("X-User-Id", "1").header("X-User-Role", "USER")
            )
            .andExpect(status().isForbidden());
        verifyNoInteractions(previews);
    }

    @Test
    @DisplayName("관리자 미리보기는 바이너리 MIME과 캐시 차단 헤더를 반환한다")
    void preview() throws Exception {
        given(storage.bucket()).willReturn("bucket");
        given(previews.read("bucket", 1L, 7L))
            .willReturn(
                new ProblemImagePreviewService.Preview(
                    "image/png",
                    new byte[] {
                        1,
                        2
                    }
                )
            );
        mvc
            .perform(
                get("/api/v1/admin/problems/images/7/content").header("X-User-Id", "1").header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}

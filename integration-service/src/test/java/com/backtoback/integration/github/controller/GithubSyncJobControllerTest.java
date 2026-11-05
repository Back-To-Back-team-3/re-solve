package com.backtoback.integration.github.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.integration.github.dto.response.GithubManualSyncResponse;
import com.backtoback.integration.github.dto.response.GithubSyncJobItemResponse;
import com.backtoback.integration.github.service.GithubSyncJobService;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

class GithubSyncJobControllerTest {

    private MockMvc mockMvc;
    private GithubSyncJobService githubSyncJobService;

    @BeforeEach
    void setUp() {
        githubSyncJobService = mock(GithubSyncJobService.class);
        GithubSyncJobController controller = new GithubSyncJobController(githubSyncJobService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("8.1.6 동기화 작업 목록 조회 성공 시 200과 페이지네이션 응답을 반환한다")
    void getSyncJobsSuccess() throws Exception {
        OffsetDateTime timestamp = OffsetDateTime.of(2026, 10, 29, 19, 0, 0, 0, ZoneOffset.ofHours(9));
        GithubSyncJobItemResponse item
            = new GithubSyncJobItemResponse(
                "77",
                "91023",
                "301",
                "FAILED",
                5,
                null,
                "Rate limit exceeded",
                timestamp,
                timestamp
            );
        PageResponse<GithubSyncJobItemResponse> pageResponse = new PageResponse<>(List.of(item), 0, 20, 1L, 1, false);

        given(githubSyncJobService.getSyncJobs(1L, "FAILED", 0, 20, "createdAt,desc")).willReturn(pageResponse);

        mockMvc
            .perform(
                get("/api/v1/integrations/github/sync-jobs")
                    .header("X-User-Id", 1L)
                    .param("status", "FAILED")
                    .param("page", "0")
                    .param("size", "20")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].syncJobId").value("77"))
            .andExpect(jsonPath("$.data.content[0].submissionId").value("91023"))
            .andExpect(jsonPath("$.data.content[0].problemId").value("301"))
            .andExpect(jsonPath("$.data.content[0].status").value("FAILED"))
            .andExpect(jsonPath("$.data.content[0].retryCount").value(5))
            .andExpect(jsonPath("$.data.content[0].errorMessage").value("Rate limit exceeded"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("8.1.7 수동 동기화 요청 성공 시 202 Accepted와 작업 등록 안내 메시지 및 상태를 반환한다")
    void requestManualSyncSuccess() throws Exception {
        GithubManualSyncResponse response = new GithubManualSyncResponse("77", "91023", "PENDING");
        given(githubSyncJobService.requestManualSync(1L, "91023")).willReturn(response);

        mockMvc
            .perform(
                post("/api/v1/integrations/github/sync-jobs")
                    .header("X-User-Id", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "submissionId": "91023"
                        }
                        """)
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("GitHub 커밋 작업을 등록했습니다."))
            .andExpect(jsonPath("$.data.syncJobId").value("77"))
            .andExpect(jsonPath("$.data.submissionId").value("91023"))
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("8.1.7 수동 동기화 요청 시 submissionId가 비어 있으면 400 Bad Request를 반환한다")
    void requestManualSyncValidationFailure() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/integrations/github/sync-jobs")
                    .header("X-User-Id", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "submissionId": ""
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("8.1.7 수동 동기화 요청 시 미연동 회원이면 404 GITHUB_NOT_CONNECTED를 반환한다")
    void requestManualSyncNotConnected() throws Exception {
        given(githubSyncJobService.requestManualSync(1L, "91023"))
            .willThrow(new IntegrationException(IntegrationErrorCode.GITHUB_NOT_CONNECTED));

        mockMvc
            .perform(
                post("/api/v1/integrations/github/sync-jobs")
                    .header("X-User-Id", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "submissionId": "91023"
                        }
                        """)
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("GITHUB_NOT_CONNECTED"));
    }

    @Test
    @DisplayName("8.1.7 수동 동기화 요청 시 동기화 불가 제출이면 409 GITHUB_SYNC_NOT_ALLOWED를 반환한다")
    void requestManualSyncNotAllowed() throws Exception {
        given(githubSyncJobService.requestManualSync(1L, "91023"))
            .willThrow(new IntegrationException(IntegrationErrorCode.GITHUB_SYNC_NOT_ALLOWED));

        mockMvc
            .perform(
                post("/api/v1/integrations/github/sync-jobs")
                    .header("X-User-Id", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "submissionId": "91023"
                        }
                        """)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("GITHUB_SYNC_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("8.1.7 SUSPENDED 회원이 수동 동기화를 요청하면 403 Forbidden을 반환한다")
    void requestManualSyncSuspendedForbidden() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/integrations/github/sync-jobs")
                    .header("X-User-Id", 1L)
                    .header("X-User-Role", "SUSPENDED")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "submissionId": "91023"
                        }
                        """)
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_ACCESS_DENIED"));
    }
}

package com.backtoback.integration.github.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.integration.github.dto.response.GithubConnectionInfoResponse;
import com.backtoback.integration.github.dto.response.GithubInstallResultResponse;
import com.backtoback.integration.github.dto.response.GithubSettingsResultResponse;
import com.backtoback.integration.github.service.GithubConnectionService;

class GithubConnectionControllerTest {

    private MockMvc mockMvc;
    private GithubConnectionService githubConnectionService;

    @BeforeEach
    void setUp() {
        githubConnectionService = mock(GithubConnectionService.class);
        GithubConnectionController controller = new GithubConnectionController(githubConnectionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("8.1.1 연동 상태 조회 성공 시 200과 연동 상세 정보를 반환한다")
    void getConnectionSuccess() throws Exception {
        GithubConnectionInfoResponse response
            = GithubConnectionInfoResponse
                .connected("kim-dev", "kim-dev", "algo-repo", true, LocalDateTime.of(2026, 10, 28, 12, 0));
        given(githubConnectionService.getConnection(1L)).willReturn(response);

        mockMvc
            .perform(get("/api/v1/integrations/github").header("X-User-Id", 1L).header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("CONNECTED"))
            .andExpect(jsonPath("$.data.githubLogin").value("kim-dev"))
            .andExpect(jsonPath("$.data.repositoryName").value("algo-repo"))
            .andExpect(jsonPath("$.data.isAutoCommitEnabled").value(true));
    }

    @Test
    @DisplayName("8.1.2 GitHub App 설치 주소 조회 성공 시 200과 설치 URL을 반환한다")
    void getInstallUrlSuccess() throws Exception {
        given(githubConnectionService.getInstallUrl(1L))
            .willReturn("https://github.com/apps/resolve-sync/installations/new?state=xyz");

        mockMvc
            .perform(
                get("/api/v1/integrations/github/install-url").header("X-User-Id", 1L).header("X-User-Role", "USER")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(
                jsonPath("$.data.installUrl").value("https://github.com/apps/resolve-sync/installations/new?state=xyz")
            );
    }

    @Test
    @DisplayName("8.1.3 GitHub App 설치 완료 성공 시 200과 연결 결과 및 안내 메시지를 반환한다")
    void installSuccess() throws Exception {
        GithubInstallResultResponse response = new GithubInstallResultResponse("CONNECTED", "algo-repo", true);
        given(githubConnectionService.install(anyLong(), anyString(), anyString())).willReturn(response);

        mockMvc
            .perform(
                post("/api/v1/integrations/github/install")
                    .header("X-User-Id", 1L)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "installationId": "58291032",
                          "state": "valid-state"
                        }
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("GitHub 저장소 연동이 완료되었습니다."))
            .andExpect(jsonPath("$.data.status").value("CONNECTED"))
            .andExpect(jsonPath("$.data.repositoryName").value("algo-repo"));
    }

    @Test
    @DisplayName("8.1.3 SUSPENDED 회원이 설치 완료를 요청하면 403 Forbidden을 반환한다")
    void installSuspendedForbidden() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/integrations/github/install")
                    .header("X-User-Id", 1L)
                    .header("X-User-Role", "SUSPENDED")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "installationId": "58291032",
                          "state": "valid-state"
                        }
                        """)
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("8.1.4 연동 설정 변경 성공 시 200과 변경 결과 및 안내 메시지를 반환한다")
    void updateSettingsSuccess() throws Exception {
        GithubSettingsResultResponse response = new GithubSettingsResultResponse(false);
        given(githubConnectionService.updateSettings(1L, false)).willReturn(response);

        mockMvc
            .perform(
                patch("/api/v1/integrations/github")
                    .header("X-User-Id", 1L)
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "isAutoCommitEnabled": false
                        }
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("연동 설정이 변경되었습니다."))
            .andExpect(jsonPath("$.data.isAutoCommitEnabled").value(false));
    }

    @Test
    @DisplayName("8.1.5 GitHub 연동 해제 성공 시 204 No Content를 반환한다")
    void disconnectSuccess() throws Exception {
        mockMvc
            .perform(delete("/api/v1/integrations/github").header("X-User-Id", 1L).header("X-User-Role", "USER"))
            .andExpect(status().isNoContent());

        verify(githubConnectionService).disconnect(1L);
    }
}

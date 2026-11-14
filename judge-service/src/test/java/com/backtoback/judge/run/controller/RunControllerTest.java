package com.backtoback.judge.run.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.run.dto.RunResponse;
import com.backtoback.judge.run.service.RunService;

/** 일반 Run 경로의 인증과 직접 입력의 공개 응답 형식을 검증한다. */
@WebMvcTest(RunController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class RunControllerTest {
    private static final String BODY = """
        {"problemId":"301","problemRevisionId":"1001","language":"python3","sourceCode":"return 1",
         "customInputs":[{"argumentTexts":{}}]}
        """;

    @MockitoBean
    private RunService runs;
    @Autowired
    private MockMvc mvc;

    /** 인증하지 않은 요청은 실행 서비스에 도달하지 않는다. */
    @Test
    @DisplayName("인증하지 않은 Run은 401을 반환한다")
    void requiresAuthentication() throws Exception {
        mvc
            .perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(runs);
    }

    /** Gateway 회원 ID를 사용하고 기대값과 측정값이 없는 응답을 구분한다. */
    @Test
    @DisplayName("본인 Run 결과에서 직접 입력의 판정과 누락 측정값을 null로 반환한다")
    void returnsCustomResult() throws Exception {
        given(runs.run(eq(7L), any()))
            .willReturn(
                new RunResponse(
                    List.of(new RunResponse.Result("CUSTOM", null, 0, null, "1", null, null, null, "")),
                    null
                )
            );
        mvc
            .perform(
                post("/api/v1/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.results[0].source").value("CUSTOM"))
            .andExpect(jsonPath("$.data.results[0].index").value(0))
            .andExpect(jsonPath("$.data.results[0].verdict").hasJsonPath())
            .andExpect(jsonPath("$.data.results[0].verdict").isEmpty())
            .andExpect(jsonPath("$.data.results[0].timeMs").hasJsonPath())
            .andExpect(jsonPath("$.data.results[0].memoryKb").hasJsonPath())
            .andExpect(jsonPath("$.data.results[0].expectedReturnText").doesNotHaveJsonPath())
            .andExpect(jsonPath("$.data.results[0].submissionId").doesNotHaveJsonPath());
    }
}

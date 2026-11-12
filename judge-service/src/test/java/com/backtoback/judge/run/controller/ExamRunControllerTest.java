package com.backtoback.judge.run.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.backtoback.judge.run.dto.ExamRunResponse;
import com.backtoback.judge.run.service.ExamRunService;

/** Contest가 사용하는 내부 응답과 요청 크기 제한을 검증한다. */
@WebMvcTest(ExamRunController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamRunControllerTest {
    private static final String PATH = "/internal/v1/judge/runs";
    private static final String BODY = """
        {"context":"EXAM","contextId":"55","memberId":"7","problemId":"301","problemRevisionId":"1001",
         "language":"python3","sourceCode":"return 1","customTests":%s}
        """;

    @MockitoBean
    private ExamRunService runs;
    @Autowired
    private MockMvc mvc;

    /** 성공 시 Contest가 직접 읽는 results와 completedAt을 반환한다. */
    @Test
    @DisplayName("시험 Run 성공 응답을 공통 래퍼 없이 반환한다")
    void returnsInternalResponse() throws Exception {
        given(runs.run(any()))
            .willReturn(
                new ExamRunResponse(
                    List.of(new ExamRunResponse.Result("CUSTOM", 0, "4", null, 2, 10)),
                    Instant.parse("2026-10-20T11:15:00Z")
                )
            );
        mvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("[{\"args\":[3]}]")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].type").value("CUSTOM"))
            .andExpect(jsonPath("$.results[0].index").value(0))
            .andExpect(jsonPath("$.results[0].output").value("4"))
            .andExpect(jsonPath("$.results[0].error").hasJsonPath())
            .andExpect(jsonPath("$.completedAt").isString());
    }

    /** 직접 입력은 최대 10개까지 받고 초과 요청을 서비스에 전달하지 않는다. */
    @Test
    @DisplayName("시험 Run 직접 입력이 10개를 초과하면 400을 반환한다")
    void rejectsTooManyCustomTests() throws Exception {
        var tests = "[" + "{\"args\":[3]},".repeat(10) + "{\"args\":[3]}]";
        mvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(tests)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }
}

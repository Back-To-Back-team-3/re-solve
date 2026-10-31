package com.backtoback.judge.submission.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.submission.dto.SubmissionSourceResponse;
import com.backtoback.judge.submission.service.SubmissionQueryService;

@WebMvcTest(SubmissionQueryController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class SubmissionQueryControllerTest {
    @MockitoBean
    private SubmissionQueryService queries;
    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("본인 코드 조회는 원본과 no-store 헤더를 반환한다")
    void returnsSource() throws Exception {
        given(queries.source(7, 1)).willReturn(new SubmissionSourceResponse("java", "code"));
        mvc
            .perform(get("/api/v1/submissions/1/source").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.sourceCode").value("code"));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "page,-1",
            "size,0",
            "size,101",
            "sort,sourceCode",
            "problemId,0"
        }
    )
    @DisplayName("페이지 범위와 허용하지 않는 정렬·식별자를 거절한다")
    void rejectsInvalidQuery(String key, String value) throws Exception {
        mvc
            .perform(
                get("/api/v1/me/submissions").param(key, value).header("X-User-Id", "7").header("X-User-Role", "USER")
            )
            .andExpect(status().isBadRequest());
    }
}

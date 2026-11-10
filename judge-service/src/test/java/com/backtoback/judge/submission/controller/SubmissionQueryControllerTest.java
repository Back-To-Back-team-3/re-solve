package com.backtoback.judge.submission.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.dto.SubmissionListRequest;
import com.backtoback.judge.submission.dto.SubmissionListResponse;
import com.backtoback.judge.submission.dto.SubmissionSourceResponse;
import com.backtoback.judge.submission.service.SubmissionQueryService;
import com.backtoback.judge.submission.service.SubmissionSummary;

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

    /**
     * 목록 응답의 연결 ID와 가린 필드의 JSON 형식을 확인한다.
     *
     * @throws Exception MockMvc 요청 실패
     */
    @Test
    @DisplayName("시험 목록 행은 contextId와 가린 판정·실행 지표를 반환한다")
    void returnsMaskedContextSummary() throws Exception {
        var item
            = SubmissionListResponse
                .from(
                    new SubmissionSummary(
                        1L,
                        2L,
                        "java",
                        Submission.Context.EXAM,
                        3L,
                        Submission.Status.COMPLETED,
                        JudgeVerdict.AC,
                        12,
                        8192,
                        LocalDateTime.of(2026, 9, 28, 10, 0)
                    )
                );
        given(queries.list(7, new SubmissionListRequest(null, null, null, null, null, null)))
            .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, false));
        mvc
            .perform(get("/api/v1/me/submissions").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].contextId").value("3"))
            .andExpect(jsonPath("$.data.content[0].sourceSubmissionId").doesNotHaveJsonPath())
            .andExpect(jsonPath("$.data.content[0].verdict").value(nullValue()))
            .andExpect(jsonPath("$.data.content[0].maxTimeMs").value(nullValue()))
            .andExpect(jsonPath("$.data.content[0].maxMemoryKb").value(nullValue()));
    }

    /**
     * 허용 범위를 벗어난 목록 조건과 시스템 내부 판정 필터를 거절하는지 확인한다.
     *
     * @param key 조회 조건 이름
     * @param value 유효하지 않은 값
     * @throws Exception MockMvc 요청 실패
     */
    @ParameterizedTest
    @CsvSource(
        {
            "page,-1",
            "size,0",
            "size,101",
            "sort,sourceCode",
            "problemId,0",
            "verdict,SYSTEM_ERROR"
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

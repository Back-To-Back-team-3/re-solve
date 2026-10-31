package com.backtoback.judge.judging.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.judging.dto.ValidationProgressResponse;
import com.backtoback.judge.judging.service.ValidationProgressService;

/** 내부 조회의 직접 응답과 작업 없음·조회 장애 구분을 확인한다. */
@WebMvcTest(ValidationProgressController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ValidationProgressControllerTest {
    @MockitoBean
    private ValidationProgressService service;
    @Autowired
    private MockMvc mvc;

    /**
     * 내부 호출은 토큰 없이 데이터 객체와 null 필드를 제공한다.
     *
     * @throws Exception 요청 검증 실패
     */
    @Test
    @DisplayName("내부 진행 조회는 래퍼 없이 필수 null 필드를 포함한다")
    void returnsDirectObject() throws Exception {
        given(service.find(2001))
            .willReturn(
                new ValidationProgressResponse("2001", "1001", "java", "QUEUED", List.of(), null, null, null, null)
            );
        mvc
            .perform(get("/internal/v1/judge/validations/2001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationId").value("2001"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.verdict").doesNotHaveJsonPath())
            .andExpect(jsonPath("$.tests").isEmpty())
            .andExpect(jsonPath("$.runtimeKey").value(nullValue()))
            .andExpect(jsonPath("$.runnerVersion").value(nullValue()))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 테스트 판정과 null 측정값을 유지하고 내부 자료를 노출하지 않는다.
     *
     * @throws Exception 요청 검증 실패
     */
    @Test
    @DisplayName("진행 응답은 대표 판정 없이 테스트별 결과만 반환한다")
    void returnsTestResults() throws Exception {
        given(service.find(2001))
            .willReturn(
                new ValidationProgressResponse(
                    "2001",
                    "1001",
                    "java",
                    "RUNNING",
                    List
                        .of(
                            new ValidationProgressResponse.TestResult(1, "WA", 2, 128),
                            new ValidationProgressResponse.TestResult(2, null, null, null)
                        ),
                    null,
                    null,
                    null,
                    null
                )
            );
        mvc
            .perform(get("/internal/v1/judge/validations/2001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").doesNotHaveJsonPath())
            .andExpect(jsonPath("$.tests[0].testOrder").value(1))
            .andExpect(jsonPath("$.tests[0].verdict").value("WA"))
            .andExpect(jsonPath("$.tests[0].timeMs").value(2))
            .andExpect(jsonPath("$.tests[0].memoryKb").value(128))
            .andExpect(jsonPath("$.tests[0].length()").value(4))
            .andExpect(jsonPath("$.tests[1].verdict").value(nullValue()))
            .andExpect(jsonPath("$.tests[1].timeMs").value(nullValue()))
            .andExpect(jsonPath("$.tests[1].memoryKb").value(nullValue()));
    }

    /**
     * 없는 작업만 404로 전달한다.
     *
     * @throws Exception 요청 검증 실패
     */
    @Test
    @DisplayName("작업이 없으면 검증 작업 없음 오류를 반환한다")
    void returnsNotFound() throws Exception {
        given(service.find(2001)).willThrow(new JudgeException(JudgeErrorCode.PROBLEM_VALIDATION_NOT_FOUND));
        mvc
            .perform(get("/internal/v1/judge/validations/2001"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_VALIDATION_NOT_FOUND"));
    }

    /**
     * DB 장애를 작업 미생성으로 오해하게 만들지 않는다.
     *
     * @throws Exception 요청 검증 실패
     */
    @Test
    @DisplayName("DB 조회 장애는 404가 아닌 내부 오류다")
    void distinguishesDatabaseFailure() throws Exception {
        given(service.find(2001)).willThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        mvc
            .perform(get("/internal/v1/judge/validations/2001"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"));
    }

    /**
     * BIGINT로 변환할 수 없는 식별자를 거부한다.
     *
     * @throws Exception 요청 검증 실패
     */
    @Test
    @DisplayName("문자열이나 BIGINT 범위를 넘는 ID는 요청 오류다")
    void rejectsInvalidId() throws Exception {
        mvc.perform(get("/internal/v1/judge/validations/invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/judge/validations/9223372036854775808")).andExpect(status().isBadRequest());
    }
}

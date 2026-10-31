package com.backtoback.judge.submission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.submission.dto.SubmissionCreateResponse;
import com.backtoback.judge.submission.service.SubmissionAcceptanceService;

/** 인증 principal·요청 검증·202 및 오류 헤더의 외부 계약을 확인한다. */
@WebMvcTest(SubmissionController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class SubmissionControllerTest {
    private static final String BODY = """
        {"problemId":"301","problemRevisionId":"1001","language":"java","sourceCode":"return 1;"}
        """;
    @MockitoBean
    private SubmissionAcceptanceService acceptance;
    @Autowired
    private MockMvc mvc;

    /**
     * 인증이 없는 요청은 접수하지 않는다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("인증 없는 제출은 401이다")
    void rejectsAnonymous() throws Exception {
        mvc
            .perform(
                post("/api/v1/submissions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
                    .header("Idempotency-Key", "key")
            )
            .andExpect(status().isUnauthorized());
    }

    /**
     * 원래 접수 결과를 공통 202 응답으로 변환한다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("Gateway 회원 ID로 접수하고 202와 원래 서버 시각을 반환한다")
    void accepts() throws Exception {
        given(acceptance.create(eq(7L), eq("key"), any()))
            .willReturn(
                new SubmissionCreateResponse(
                    "2001",
                    1,
                    "QUEUED",
                    OffsetDateTime.parse("2026-10-31T09:00:00+09:00"),
                    "/api/v1/submissions/2001"
                )
            );
        mvc
            .perform(
                post("/api/v1/submissions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .header("Idempotency-Key", "key")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("제출이 접수되었습니다."))
            .andExpect(jsonPath("$.data.submissionId").value("2001"))
            .andExpect(jsonPath("$.data.judgeAttempt").value(1))
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andExpect(jsonPath("$.data.submittedAt").value("2026-10-31T09:00:00+09:00"));
        verify(acceptance).create(eq(7L), eq("key"), any());
    }

    /**
     * 필수 헤더와 잘못된 바디를 공통 400으로 거절한다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("멱등 헤더 누락과 필수 필드 누락은 400이다")
    void rejectsInvalidRequest() throws Exception {
        mvc
            .perform(
                post("/api/v1/submissions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(
                post("/api/v1/submissions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .header("Idempotency-Key", "key")
            )
            .andExpect(status().isBadRequest());
    }

    /**
     * 서비스의 제한 대기 시간을 공통 오류 응답 헤더로 전달한다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("429 오류는 공통 오류 코드와 Retry-After를 보존한다")
    void returnsRetryAfter() throws Exception {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        given(acceptance.create(eq(7L), eq("key"), any()))
            .willThrow(new JudgeException(JudgeErrorCode.SUBMISSION_RATE_LIMITED, headers, null));
        mvc
            .perform(
                post("/api/v1/submissions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .header("Idempotency-Key", "key")
            )
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_RATE_LIMITED"));
    }
}

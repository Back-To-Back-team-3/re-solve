package com.backtoback.problem.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemValidationResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemValidationService;

/** 검증 접수의 관리자 권한·추적 ID와 내부 조회 응답 계약을 확인한다. */
@WebMvcTest(AdminProblemValidationController.class)
@Import(
    {
        SecurityConfiguration.class,
        ProblemCreateBodyReader.class,
        CorrelationIdFilter.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class AdminProblemValidationControllerTest {
    private static final String PATH = "/api/v1/admin/problems/1/revisions/2/validations";
    private static final String BODY = "{\"language\":\"java\",\"sourceCode\":\"class Solution {}\"}";
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemValidationService service;

    /**
     * 관리자의 원문과 추적 ID를 전달하고 최초 접수 형태를 반환한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("관리자 검증 접수는 추적 ID를 전달하고 202 공통 응답을 반환한다")
    void accepted() throws Exception {
        given(service.accept(anyLong(), anyLong(), anyLong(), anyString(), any(), anyString(), any()))
            .willReturn(new ProblemValidationResponse("3", "2", "java", "REQUESTED"));
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .header("X-Correlation-Id", "request-219")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.validationId").value("3"))
            .andExpect(jsonPath("$.data.status").value("REQUESTED"));
        verify(service)
            .accept(
                eq(1L),
                eq(2L),
                eq(10L),
                eq("key"),
                eq(new ProblemValidationRequest("java", "class Solution {}")),
                eq("request-219"),
                any()
            );
    }

    /**
     * 일반 회원은 관리자 검증을 접수하지 못한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("일반 회원의 검증 접수는 403으로 거절한다")
    void forbidden() throws Exception {
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "USER")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            )
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /**
     * 빈 코드와 누락된 멱등키를 서비스 호출 전에 거절한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("빈 코드와 누락된 멱등키는 400으로 거절한다")
    void invalid() throws Exception {
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.replace("class Solution {}", " "))
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            )
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

}

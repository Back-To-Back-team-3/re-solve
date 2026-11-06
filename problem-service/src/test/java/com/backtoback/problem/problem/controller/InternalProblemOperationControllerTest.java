package com.backtoback.problem.problem.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemStateOperation;
import com.backtoback.problem.problem.service.ProblemOperationService;

/** 실제 작업 조회 경로의 내부 응답 계약을 검증한다. */
@WebMvcTest(InternalProblemOperationController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class InternalProblemOperationControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemOperationService service;

    /**
     * 인증 헤더 없이 원래 작업 결과를 공통 래퍼 없이 제공한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("내부 작업 조회는 원래 결과와 문자열 버전을 직접 반환한다")
    void snapshot() throws Exception {
        var operation
            = ProblemStateOperation
                .publication("operation", Problem.create(10L, ProblemScope.GENERAL), "key", "hash", 1);
        operation.commit(2, "{}");
        given(service.find(1L, "operation")).willReturn(operation);
        mvc
            .perform(get("/internal/v1/problems/1/operations/operation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operationId").value("operation"))
            .andExpect(jsonPath("$.state").value("COMMITTED"))
            .andExpect(jsonPath("$.sourceVersion").value("2"))
            .andExpect(jsonPath("$.resourceStatus").value("PUBLIC"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 없는 작업을 임의 종결 상태로 바꾸지 않고 404로 반환한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("없는 내부 작업은 404를 반환한다")
    void missing() throws Exception {
        given(service.find(1L, "missing")).willThrow(new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        mvc
            .perform(get("/internal/v1/problems/1/operations/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_NOT_FOUND"));
    }
}

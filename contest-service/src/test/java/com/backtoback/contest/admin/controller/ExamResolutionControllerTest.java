package com.backtoback.contest.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.admin.dto.ExamResolutionResponse;
import com.backtoback.contest.admin.service.ExamResolutionService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 관리자 종결 API의 인증과 공통 응답을 확인한다. */
@WebMvcTest(ExamResolutionController.class)
@ContextConfiguration(
    classes = {
        ExamResolutionController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamResolutionControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamResolutionService service;

    /** 전달 전 실패의 회차 0 요청도 서비스로 전달한다. */
    @Test
    @DisplayName("관리자의 회차 0 종결 요청에 공통 성공 응답을 반환한다")
    void adminCanResolveAttemptZero() throws Exception {
        given(service.resolve(eq("EXAM"), eq("4001"), any(), any()))
            .willReturn(new ExamResolutionResponse("zero", "EXAM", "4001", "FAILED", true, "4"));
        mvc
            .perform(
                post("/api/v1/admin/contest-submissions/EXAM/4001/resolution")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"operationId":"zero","action":"ZERO_CONFIRM","expectedJudgeAttempt":0,"reason":"종결"}
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultRevision").value("4"));
    }

    /** 일반 회원은 서비스 실행 전에 관리자 경로에서 거절한다. */
    @Test
    @DisplayName("일반 회원은 재확정 API를 호출하지 못한다")
    void normalUserCannotRefinalize() throws Exception {
        mvc
            .perform(
                post("/api/v1/admin/exams/55/results/refinalization")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"operationId":"op","expectedResultRevision":"3","reason":"사유"}
                        """)
            )
            .andExpect(status().isForbidden());
    }
}

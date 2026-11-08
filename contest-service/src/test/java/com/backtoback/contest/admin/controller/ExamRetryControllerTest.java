package com.backtoback.contest.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.contest.admin.dto.ExamRetryResponse;
import com.backtoback.contest.admin.service.ExamRetryService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 공개 재처리 경로와 관리자 조회 경로의 인증을 확인한다. */
@WebMvcTest(ExamRetryController.class)
@ContextConfiguration(
    classes = {
        ExamRetryController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamRetryControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamRetryService service;

    @Test
    @DisplayName("일반 회원의 시험 재처리 요청을 서비스 권한 검증으로 전달한다")
    void retryReachesService() throws Exception {
        given(service.request(eq("55"), eq("4001"), any(), any()))
            .willReturn(new ExamRetryResponse("op", "PENDING", "4001", "9001", 1, null));
        mvc
            .perform(
                post("/api/v1/exams/55/submissions/4001/retry")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"operationId":"op","expectedJudgeAttempt":1,"reason":"재처리"}
                        """)
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.state").value("PENDING"));
    }

    @Test
    @DisplayName("일반 회원은 관리자 작업 조회 경로에 접근하지 못한다")
    void adminPathRequiresAdmin() throws Exception {
        mvc
            .perform(get("/api/v1/admin/contest-operations/op").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 쿼리로 슬래시가 포함된 작업 ID를 조회한다")
    void adminCanQueryOperationIdWithSlash() throws Exception {
        // when & then
        mvc
            .perform(
                get(java.net.URI.create("/api/v1/admin/contest-operations?operationId=repair%2F1"))
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isOk());
        verify(service).adminOperation("repair/1", new GatewayUser("7", UserRole.ADMIN));
    }

    @Test
    @DisplayName("시험 작업 쿼리는 시험 ID와 슬래시가 포함된 작업 ID를 권한 검증에 전달한다")
    void examQueryDelegatesWithExamAndOperationIds() throws Exception {
        // when & then
        mvc
            .perform(
                get("/api/v1/exams/55/operations")
                    .param("operationId", "repair/1")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().isOk());
        verify(service).examOperation("55", "repair/1", new GatewayUser("7", UserRole.USER));
    }
}

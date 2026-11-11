package com.backtoback.judge.admin.controller;

import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.admin.service.JudgeJobOperationService;
import com.backtoback.judge.global.security.SecurityConfiguration;

/** 관리자 경로의 인증·권한·요청 검증을 실제 MVC 필터에서 확인한다. */
@WebMvcTest(AdminJudgeJobController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class AdminJudgeJobControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private JudgeJobOperationService operations;

    /**
     * 관리자만 작업 목록을 조회할 수 있는지 확인한다.
     *
     * @throws Exception MVC 요청을 수행하지 못한 경우
     */
    @Test
    @DisplayName("채점 작업 목록은 미인증과 일반 회원을 거절하고 관리자에게만 공개한다")
    void requiresAdmin() throws Exception {
        given(operations.list(any())).willReturn(Page.empty());
        mvc.perform(get("/api/v1/admin/judge-jobs")).andExpect(status().isUnauthorized());
        mvc
            .perform(get("/api/v1/admin/judge-jobs").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
        mvc
            .perform(get("/api/v1/admin/judge-jobs").header("X-User-Id", "7").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /**
     * 복구의 집행자는 요청 본문 대신 Gateway 인증 정보에서 가져온다.
     *
     * @throws Exception MVC 요청을 수행하지 못한 경우
     */
    @Test
    @DisplayName("관리자 복구는 인증 관리자 ID와 멱등키를 사용하고 최초 상태를 202로 반환한다")
    void acceptsRecovery() throws Exception {
        given(operations.recover(1, 7, "key", "복구", "192.0.2.10")).willReturn("RETRY_WAITING");
        mvc
            .perform(
                post("/api/v1/admin/judge-jobs/1/recoveries")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.10");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"복구\"}")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.judgeJobId").value("1"))
            .andExpect(jsonPath("$.data.status").value("RETRY_WAITING"));
        verify(operations).recover(1, 7, "key", "복구", "192.0.2.10");
    }

    /**
     * 잘못된 페이지·정렬·키·사유를 서비스 호출 전에 거절한다.
     *
     * @throws Exception MVC 요청을 수행하지 못한 경우
     */
    @Test
    @DisplayName("허용하지 않는 정렬과 비어 있는 복구 사유는 400으로 거절한다")
    void rejectsInvalidRequests() throws Exception {
        mvc
            .perform(
                get("/api/v1/admin/judge-jobs")
                    .param("sort", "sourceCode,desc")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(
                get("/api/v1/admin/judge-jobs")
                    .param("contextId", "9223372036854775808")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(
                post("/api/v1/admin/judge-jobs/1/recoveries")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\" \"}")
            )
            .andExpect(status().isBadRequest());
    }
}

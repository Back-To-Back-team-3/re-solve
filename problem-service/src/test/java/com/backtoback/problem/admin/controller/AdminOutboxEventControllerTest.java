package com.backtoback.problem.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.admin.service.AdminOutboxEventService;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;

/** 서비스별 운영 경로의 관리자 권한·요청 길이·식별자 응답을 확인한다. */
@WebMvcTest(AdminOutboxEventController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class AdminOutboxEventControllerTest {
    private static final String PATH = "/api/v1/admin/problems/outbox-events/1/reprocesses";
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AdminOutboxEventService service;

    /**
     * 서비스별 경로에서 최대 길이 사유와 인증한 관리자·요청 주소를 전달한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("관리자는 500자 사유로 Problem 재처리를 접수하고 문자열 이벤트 ID를 202로 받는다")
    void acceptsAdminRequest() throws Exception {
        String reason = "가".repeat(500);
        given(service.reprocess(1, 7, reason, "127.0.0.1")).willReturn(1L);
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"" + reason + "\"}")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.outboxEventId").value("1"));
        verify(service).reprocess(1, 7, reason, "127.0.0.1");
    }

    /**
     * 미인증·일반 회원의 운영 요청을 서비스 실행 전에 차단한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("Outbox 재처리는 미인증 요청에 401, 일반 회원에 403을 반환한다")
    void requiresAdmin() throws Exception {
        mvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"복구\"}"))
            .andExpect(status().isUnauthorized());
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"복구\"}")
            )
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /**
     * 잘못된 사유를 DB 저장 전에 거절한다.
     *
     * @param reason 공백·빈 값 또는 저장 한도 초과 사유
     * @throws Exception MVC 요청 실패
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            " ",
            "too-long"
        }
    )
    @DisplayName("빈 사유·공백 사유·501자 사유는 400으로 거절한다")
    void rejectsInvalidReason(String reason) throws Exception {
        String value = reason.equals("too-long") ? "가".repeat(501) : reason;
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"" + value + "\"}")
            )
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /**
     * 존재할 수 없는 행 식별자를 서비스 호출 전에 거절한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("0 이하 Outbox ID는 400으로 거절한다")
    void rejectsNonpositiveId() throws Exception {
        mvc
            .perform(
                post(PATH.replace("/1/", "/0/"))
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"복구\"}")
            )
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

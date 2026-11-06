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
import com.backtoback.problem.problem.dto.request.ProblemPublicationRequest;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemPublicationService;

/** 문제 공개의 관리자 권한·추적 ID와 공개 응답 계약을 확인한다. */
@WebMvcTest(AdminProblemPublicationController.class)
@Import(
    {
        SecurityConfiguration.class,
        ProblemCreateBodyReader.class,
        CorrelationIdFilter.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class AdminProblemPublicationControllerTest {
    private static final String PATH = "/api/v1/admin/problems/1/publication";
    private static final String BODY = "{\"rowVersion\":1}";
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemPublicationService service;

    /**
     * 관리자의 기대 버전과 추적 ID를 전달하고 최초 공개 형태를 반환한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("관리자 문제 공개는 추적 ID를 전달하고 200 공통 응답을 반환한다")
    void accepted() throws Exception {
        given(service.publish(anyLong(), anyLong(), anyString(), any(), anyString(), any()))
            .willReturn(
                new ProblemPublicationService.Result(
                    200,
                    "{\"success\":true,\"message\":\"저장된 공개 안내\",\"data\":{\"visibility\":\"PUBLIC\",\"rowVersion\":2}}"
                )
            );
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .header("X-Correlation-Id", "request-222")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("저장된 공개 안내"))
            .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.data.rowVersion").value(2));
        verify(
            service
        ).publish(eq(1L), eq(10L), eq("key"), eq(new ProblemPublicationRequest(1)), eq("request-222"), eq("127.0.0.1"));
    }

    /**
     * 확정 실패의 저장된 상태·메시지·상세를 현재 오류 매핑으로 바꾸지 않는다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("저장된 확정 실패 응답을 HTTP 상태와 함께 그대로 반환한다")
    void replaysFailure() throws Exception {
        given(service.publish(anyLong(), anyLong(), anyString(), any(), anyString(), any()))
            .willReturn(new ProblemPublicationService.Result(422, """
                {"success":false,"error":{"code":"PROBLEM_VERSION_CONFLICT","message":"저장된 오류 안내",
                "details":[{"field":"rowVersion","reason":"기존 상세"}]}}
                """));
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            )
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PROBLEM_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.error.message").value("저장된 오류 안내"))
            .andExpect(jsonPath("$.error.details[0].reason").value("기존 상세"));
    }

    /**
     * 일반 회원은 관리자 공개를 요청하지 못한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("일반 회원의 문제 공개는 403으로 거절한다")
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
     * 잘못된 버전과 누락된 멱등키를 서비스 호출 전에 거절한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("잘못된 버전과 누락된 멱등키는 400으로 거절한다")
    void invalid() throws Exception {
        mvc
            .perform(
                post(PATH)
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rowVersion\":0}")
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

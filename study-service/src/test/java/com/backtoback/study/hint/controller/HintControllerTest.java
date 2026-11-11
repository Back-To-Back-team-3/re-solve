package com.backtoback.study.hint.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.hint.service.HintRequestService;

/**
 * 힌트 요청 API 요청 바인딩·응답 변환을 검증한다(API 명세서 §6.1.1).
 * JpaAuditingConfiguration 때문에 @WebMvcTest는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7)
 * standalone MockMvc로 작성한다.
 */
class HintControllerTest {

    private MockMvc mockMvc;
    private HintRequestService hintRequestService;

    @BeforeEach
    void setUp() {
        hintRequestService = mock(HintRequestService.class);
        HintController controller = new HintController(hintRequestService);
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder
            .getContext()
            .setAuthentication(new TestingAuthenticationToken(new GatewayUser("7", UserRole.USER), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("6.1.1 정상 요청이면 202와 힌트 요청 결과를 반환한다")
    void requestHint_validRequest_returns202() throws Exception {
        given(hintRequestService.request(eq(7L), eq(100L), eq(1)))
            .willReturn(new HintAcceptResult(42L, HintRequestStatus.REQUESTED, null, 3));

        mockMvc
            .perform(post("/api/v1/hint-requests").contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "problemId": "100",
                    "level": 1
                }"""))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.hintRequestId").value("42"))
            .andExpect(jsonPath("$.data.status").value("REQUESTED"))
            .andExpect(jsonPath("$.data.content").doesNotExist())
            .andExpect(jsonPath("$.data.todayUsedCount").value(3))
            .andExpect(jsonPath("$.data.dailyLimit").value(20));
    }

    @Test
    @DisplayName("6.1.1 캐시 히트이면 202와 content를 함께 반환한다")
    void requestHint_cacheHit_returnContentInResponse() throws Exception {
        given(hintRequestService.request(eq(7L), eq(100L), eq(1)))
            .willReturn(new HintAcceptResult(43L, HintRequestStatus.READY, "힌트 내용입니다", 5));

        mockMvc
            .perform(post("/api/v1/hint-requests").contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "problemId": "100",
                    "level": 1
                }"""))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.content").value("힌트 내용입니다"));
    }

    @Test
    @DisplayName("6.1.1 problemId가 없으면 400을 반환한다")
    void requestHint_missingProblemId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/hint-requests").contentType(MediaType.APPLICATION_JSON).content("""
            {
                "level": 1
            }""")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6.1.1 level이 0이면 400을 반환한다")
    void requestHint_levelZero_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/hint-requests").contentType(MediaType.APPLICATION_JSON).content("""
            {
                "problemId": "100",
                "level": 0
            }""")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6.1.1 level이 4이면 400을 반환한다")
    void requestHint_levelFour_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/hint-requests").contentType(MediaType.APPLICATION_JSON).content("""
            {
                "problemId": "100",
                "level": 4
            }""")).andExpect(status().isBadRequest());
    }
}

package com.backtoback.study.operation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.study.operation.dto.StudyCloseResponse;
import com.backtoback.study.operation.service.StudyOperationService;

/**
 * 관리자 스터디 강제 종료 API 요청 바인딩·응답 변환을 검증한다(API 명세서 §5.6.1).
 * standalone MockMvc로 작성한다(컨벤션 매뉴얼 6.7).
 */
class StudyAdminControllerTest {

    private MockMvc mockMvc;
    private StudyOperationService studyOperationService;

    @BeforeEach
    void setUp() {
        studyOperationService = mock(StudyOperationService.class);
        StudyAdminController controller = new StudyAdminController(studyOperationService);
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder
            .getContext()
            .setAuthentication(new TestingAuthenticationToken(new GatewayUser("7", UserRole.ADMIN), null));
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    @DisplayName("§5.6.1 정상 요청이면 200과 studyId를 반환한다")
    void forceCloseStudy_validRequest_returns200() throws Exception {
        // given
        given(studyOperationService.forceCloseStudy(anyLong(), anyLong(), any(), any(), any()))
            .willReturn(new StudyCloseResponse("1"));

        // when & then
        mockMvc
            .perform(post("/api/v1/admin/studies/1/close").contentType(MediaType.APPLICATION_JSON).content("""
                {
                    "reason": "정책 위반"
                }"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.studyId").value("1"));
    }

    @Test
    @DisplayName("§5.6.1 reason이 없으면 400을 반환한다")
    void forceCloseStudy_missingReason_returns400() throws Exception {
        // when & then
        mockMvc
            .perform(post("/api/v1/admin/studies/1/close").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }
}

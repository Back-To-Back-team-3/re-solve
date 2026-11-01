package com.backtoback.study.membership.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.backtoback.study.membership.service.StudyCommandService;

/**
 * 스터디 생성 API 요청 바인딩·응답 변환을 검증한다(API 명세서 §5.2.1).
 * {@code JpaAuditingConfiguration} 때문에 {@code @WebMvcTest}는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7)
 * standalone MockMvc로 작성한다.
 */
class StudyCommandControllerTest {

    private MockMvc mockMvc;
    private StudyCommandService studyCommandService;

    @BeforeEach
    void setUp() {
        studyCommandService = mock(StudyCommandService.class);
        StudyCommandController controller = new StudyCommandController(studyCommandService);
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
    @DisplayName("5.2.1 정상 요청이면 201과 studyId를 반환한다")
    void createStudy_success() throws Exception {
        // given
        given(studyCommandService.createStudy(eq(7L), any(), anyString())).willReturn(10L);

        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies")
                    .header("X-Correlation-Id", "corr-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "코딩테스트 스터디",
                            "description": "설명",
                            "goal": "EMPLOYMENT",
                            "skillLevel": "LV2",
                            "preferredLanguage": "java",
                            "activeTimeSlot": "EVENING",
                            "capacity": 5,
                            "joinType": "INSTANT",
                            "isAutoReopen": false
                        }""")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.studyId").value("10"));
    }

    @Test
    @DisplayName("5.2.1 name이 없으면 400을 반환한다")
    void createStudy_missingName_returns400() throws Exception {
        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies")
                    .header("X-Correlation-Id", "corr-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "description": "설명",
                            "goal": "EMPLOYMENT",
                            "skillLevel": "LV2",
                            "preferredLanguage": "java",
                            "activeTimeSlot": "EVENING",
                            "capacity": 5,
                            "joinType": "INSTANT",
                            "isAutoReopen": false
                        }""")
            )
            .andExpect(status().isBadRequest());
    }
}

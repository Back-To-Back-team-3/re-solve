package com.backtoback.study.membership.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.service.MemberStatusGuard;
import com.backtoback.study.membership.service.StudyCommandService;

/**
 * 스터디 생성 API 요청 바인딩·응답 변환을 검증한다(API 명세서 §5.2.1).
 * {@code JpaAuditingConfiguration} 때문에 {@code @WebMvcTest}는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7)
 * standalone MockMvc로 작성한다.
 */
class StudyCommandControllerTest {

    private MockMvc mockMvc;
    private StudyCommandService studyCommandService;
    private MemberStatusGuard memberStatusGuard;

    @BeforeEach
    void setUp() {
        studyCommandService = mock(StudyCommandService.class);
        memberStatusGuard = mock(MemberStatusGuard.class);
        StudyCommandController controller = new StudyCommandController(memberStatusGuard, studyCommandService);
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder
            .getContext()
            .setAuthentication(new TestingAuthenticationToken(new GatewayUser("7", UserRole.USER), null));
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    @DisplayName("5.2.1 정상 요청이면 201과 studyId를 반환한다")
    void createStudy_success() throws Exception {
        // given
        given(studyCommandService.createStudy(eq(7L), any(), anyString())).willReturn(10L);

        // when & then
        mockMvc
            .perform(post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content("""
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
                }"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.studyId").value("10"));
    }

    @Test
    @DisplayName("5.2.1 name이 없으면 400을 반환한다")
    void createStudy_missingName_returns400() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content("""
            {
                "description": "설명",
                "goal": "EMPLOYMENT",
                "skillLevel": "LV2",
                "preferredLanguage": "java",
                "activeTimeSlot": "EVENING",
                "capacity": 5,
                "joinType": "INSTANT",
                "isAutoReopen": false
            }""")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5.2.1 정지 회원이면 생성 서비스를 호출하지 않고 403 MEMBER_SUSPENDED를 반환한다")
    void createStudy_suspended_returns403() throws Exception {
        // given
        willThrow(new StudyException(StudyErrorCode.MEMBER_SUSPENDED)).given(memberStatusGuard).requireNotSuspended(7L);

        // when & then
        mockMvc
            .perform(post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content("""
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
                }"""))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value(StudyErrorCode.MEMBER_SUSPENDED.getCode()));
        then(studyCommandService).should(never()).createStudy(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "LV1",
            "LV2",
            "LV3"
        }
    )
    @DisplayName("5.2.1 skillLevel이 LV1~LV3이면 201을 반환한다")
    void createStudy_allowedSkillLevel_returns201(String skillLevel) throws Exception {
        // given
        given(studyCommandService.createStudy(eq(7L), any(), anyString())).willReturn(10L);

        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content(bodyWithSkillLevel(skillLevel))
            )
            .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "LV0",
            "MASTER",
            "LV4",
            "lv1"
        }
    )
    @DisplayName("5.2.1 skillLevel이 LV1~LV3이 아니면 생성 서비스를 호출하지 않고 400 COMMON_INVALID_REQUEST를 반환한다")
    void createStudy_disallowedSkillLevel_returns400(String skillLevel) throws Exception {
        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content(bodyWithSkillLevel(skillLevel))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_INVALID_REQUEST.getCode()))
            .andExpect(jsonPath("$.error.details[*].field").value(hasItem("skillLevel")));
        then(studyCommandService).should(never()).createStudy(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "EMPLOYMENT",
            "CONTEST",
            "STUDY"
        }
    )
    @DisplayName("5.2.1 goal이 EMPLOYMENT·CONTEST·STUDY 중 하나면 201을 반환한다")
    void createStudy_allowedGoal_returns201(String goal) throws Exception {
        // given
        given(studyCommandService.createStudy(eq(7L), any(), anyString())).willReturn(10L);

        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content(body("LV2", goal, "EVENING"))
            )
            .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "취업",
            "employment",
            "JOB"
        }
    )
    @DisplayName("5.2.1 goal이 값 목록에 없으면 생성 서비스를 호출하지 않고 400 COMMON_INVALID_REQUEST를 반환한다")
    void createStudy_disallowedGoal_returns400(String goal) throws Exception {
        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies").contentType(MediaType.APPLICATION_JSON).content(body("LV2", goal, "EVENING"))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_INVALID_REQUEST.getCode()))
            .andExpect(jsonPath("$.error.details[*].field").value(hasItem("goal")));
        then(studyCommandService).should(never()).createStudy(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "MORNING",
            "AFTERNOON",
            "EVENING",
            "NIGHT"
        }
    )
    @DisplayName("5.2.1 activeTimeSlot이 MORNING·AFTERNOON·EVENING·NIGHT 중 하나면 201을 반환한다")
    void createStudy_allowedActiveTimeSlot_returns201(String activeTimeSlot) throws Exception {
        // given
        given(studyCommandService.createStudy(eq(7L), any(), anyString())).willReturn(10L);

        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("LV2", "EMPLOYMENT", activeTimeSlot))
            )
            .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "저녁",
            "evening",
            "DAWN"
        }
    )
    @DisplayName("5.2.1 activeTimeSlot이 값 목록에 없으면 생성 서비스를 호출하지 않고 400 COMMON_INVALID_REQUEST를 반환한다")
    void createStudy_disallowedActiveTimeSlot_returns400(String activeTimeSlot) throws Exception {
        // when & then
        mockMvc
            .perform(
                post("/api/v1/studies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("LV2", "EMPLOYMENT", activeTimeSlot))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_INVALID_REQUEST.getCode()))
            .andExpect(jsonPath("$.error.details[*].field").value(hasItem("activeTimeSlot")));
        then(studyCommandService).should(never()).createStudy(any(), any(), any());
    }

    private static String bodyWithSkillLevel(String skillLevel) {
        return body(skillLevel, "EMPLOYMENT", "EVENING");
    }

    private static String body(String skillLevel, String goal, String activeTimeSlot) {
        return """
            {
                "name": "코딩테스트 스터디",
                "description": "설명",
                "goal": "%s",
                "skillLevel": "%s",
                "preferredLanguage": "java",
                "activeTimeSlot": "%s",
                "capacity": 5,
                "joinType": "INSTANT",
                "isAutoReopen": false
            }""".formatted(goal, skillLevel, activeTimeSlot);
    }
}

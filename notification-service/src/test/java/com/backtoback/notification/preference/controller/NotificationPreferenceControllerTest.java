package com.backtoback.notification.preference.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.notification.preference.dto.request.NotificationPreferenceUpdateRequest;
import com.backtoback.notification.preference.dto.response.NotificationPreferenceResponse;
import com.backtoback.notification.preference.service.NotificationPreferenceService;

class NotificationPreferenceControllerTest {

    private MockMvc mockMvc;
    private NotificationPreferenceService preferenceService;

    @BeforeEach
    void setUp() {
        preferenceService = mock(NotificationPreferenceService.class);
        NotificationPreferenceController controller = new NotificationPreferenceController(preferenceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("7.1.6 알림 수신 설정 조회 요청 시 200 OK와 종류별 수신 설정을 반환한다")
    void getPreferencesSuccess() throws Exception {
        // given
        NotificationPreferenceResponse response = NotificationPreferenceResponse.defaultPreference();
        given(preferenceService.getPreferences(eq(1L))).willReturn(response);

        // when & then
        mockMvc
            .perform(
                get("/api/v1/notifications/preferences").header("X-User-Id", "1").accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.data.isExamContestEnabled").value(true))
            .andExpect(jsonPath("$.data.isStudyApplicationEnabled").value(true))
            .andExpect(jsonPath("$.data.isAssignmentEnabled").value(true))
            .andExpect(jsonPath("$.data.isLevelChangedEnabled").value(true))
            .andExpect(jsonPath("$.data.isHintReadyEnabled").value(true))
            .andExpect(jsonPath("$.data.isGithubSyncEnabled").value(true));

        verify(preferenceService).getPreferences(eq(1L));
    }

    @Test
    @DisplayName("7.1.6 알림 수신 설정 조회 시 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED를 반환한다")
    void getPreferencesWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(get("/api/v1/notifications/preferences"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("7.1.6 알림 수신 설정 부분 변경 요청 시 200 OK와 변경된 설정을 반환한다")
    void updatePreferencesSuccess() throws Exception {
        // given
        NotificationPreferenceUpdateRequest expectedRequest
            = new NotificationPreferenceUpdateRequest(null, null, false, null, null, null);
        NotificationPreferenceResponse updatedResponse
            = new NotificationPreferenceResponse(true, true, false, true, true, true);

        given(preferenceService.updatePreferences(eq(1L), eq(expectedRequest))).willReturn(updatedResponse);

        // when & then
        mockMvc
            .perform(
                patch("/api/v1/notifications/preferences")
                    .header("X-User-Id", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"isAssignmentEnabled\":false}")
                    .accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.data.isExamContestEnabled").value(true))
            .andExpect(jsonPath("$.data.isStudyApplicationEnabled").value(true))
            .andExpect(jsonPath("$.data.isAssignmentEnabled").value(false))
            .andExpect(jsonPath("$.data.isLevelChangedEnabled").value(true))
            .andExpect(jsonPath("$.data.isHintReadyEnabled").value(true))
            .andExpect(jsonPath("$.data.isGithubSyncEnabled").value(true));

        verify(preferenceService).updatePreferences(eq(1L), eq(expectedRequest));
    }

    @Test
    @DisplayName("7.1.6 알림 수신 설정 변경 시 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED를 반환한다")
    void updatePreferencesWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(
                patch("/api/v1/notifications/preferences")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"isAssignmentEnabled\":false}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("7.1.6 알림 수신 설정 변경 시 잘못된 JSON 형식이 전달되면 400 BAD_REQUEST와 COMMON_INVALID_REQUEST를 반환한다")
    void updatePreferencesWithMalformedBodyReturnsBadRequest() throws Exception {
        // when & then
        mockMvc
            .perform(
                patch("/api/v1/notifications/preferences")
                    .header("X-User-Id", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{invalid-json}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("7.1.6 알림 수신 설정 변경 시 boolean 대신 문자열(\"false\")이 전달되면 400 BAD_REQUEST를 반환한다")
    void updatePreferencesWithStringBooleanReturnsBadRequest() throws Exception {
        // when & then
        mockMvc
            .perform(
                patch("/api/v1/notifications/preferences")
                    .header("X-User-Id", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"isAssignmentEnabled\":\"false\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }
}

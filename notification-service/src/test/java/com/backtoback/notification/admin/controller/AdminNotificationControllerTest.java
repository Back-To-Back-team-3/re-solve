package com.backtoback.notification.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.notification.admin.dto.request.AdminNotificationBroadcastRequest;
import com.backtoback.notification.admin.dto.response.AdminNotificationBroadcastResponse;
import com.backtoback.notification.admin.service.AdminNotificationService;
import com.backtoback.notification.notification.domain.NotificationType;

class AdminNotificationControllerTest {

    private MockMvc mockMvc;
    private AdminNotificationService adminNotificationService;

    @BeforeEach
    void setUp() {
        adminNotificationService = mock(AdminNotificationService.class);
        AdminNotificationController controller = new AdminNotificationController(adminNotificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("7.2.1 관리자 권한(ADMIN)으로 전역 공지 발송 시 200 OK와 성공 응답을 반환한다")
    void broadcastSuccess() throws Exception {
        // given
        AdminNotificationBroadcastResponse response
            = new AdminNotificationBroadcastResponse(
                NotificationType.SYSTEM_NOTICE,
                "시스템 정기 점검 안내",
                LocalDateTime.of(2026, 11, 7, 15, 30, 0),
                5
            );
        given(adminNotificationService.broadcastSystemNotice(eq(1L), any(AdminNotificationBroadcastRequest.class)))
            .willReturn(response);

        String requestBody = """
            {
                "title": "시스템 정기 점검 안내",
                "content": "2026년 11월 8일 02:00 ~ 04:00 정기 점검이 진행될 예정입니다.",
                "linkUrl": "/notices/1"
            }
            """;

        // when & then
        mockMvc
            .perform(
                post("/api/v1/admin/notifications/broadcast")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.notificationType").value("SYSTEM_NOTICE"))
            .andExpect(jsonPath("$.data.title").value("시스템 정기 점검 안내"))
            .andExpect(jsonPath("$.data.deliveredSseCount").value(5));

        verify(adminNotificationService).broadcastSystemNotice(eq(1L), any(AdminNotificationBroadcastRequest.class));
    }

    @Test
    @DisplayName("관리자 권한이 아닌 일반 사용자(USER)가 요청하면 403 Forbidden을 반환한다")
    void broadcastForbiddenWhenNotAdmin() throws Exception {
        String requestBody = """
            {
                "title": "시스템 정기 점검 안내",
                "content": "2026년 11월 8일 02:00 ~ 04:00 정기 점검이 진행될 예정입니다."
            }
            """;

        mockMvc
            .perform(
                post("/api/v1/admin/notifications/broadcast")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("X-User-Role 헤더가 누락되면 403 Forbidden을 반환한다")
    void broadcastForbiddenWhenRoleHeaderMissing() throws Exception {
        String requestBody = """
            {
                "title": "시스템 정기 점검 안내",
                "content": "2026년 11월 8일 02:00 ~ 04:00 정기 점검이 진행될 예정입니다."
            }
            """;

        mockMvc
            .perform(
                post("/api/v1/admin/notifications/broadcast")
                    .header("X-User-Id", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("제목 또는 내용이 비어 있으면 400 Bad Request를 반환한다")
    void broadcastBadRequestWhenFieldsBlank() throws Exception {
        String requestBody = """
            {
                "title": "",
                "content": ""
            }
            """;

        mockMvc
            .perform(
                post("/api/v1/admin/notifications/broadcast")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isBadRequest());
    }
}

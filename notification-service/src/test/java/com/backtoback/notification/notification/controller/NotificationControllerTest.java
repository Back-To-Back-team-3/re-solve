package com.backtoback.notification.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.notification.global.error.NotificationException;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.dto.response.NotificationPageResponse;
import com.backtoback.notification.notification.dto.response.NotificationReadAllResponse;
import com.backtoback.notification.notification.dto.response.NotificationResponse;
import com.backtoback.notification.notification.dto.response.UnreadCountResponse;
import com.backtoback.notification.notification.service.NotificationService;

class NotificationControllerTest {

    private MockMvc mockMvc;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        NotificationController controller = new NotificationController(notificationService);
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("7.1.2 알림 목록 페이징 조회 요청 시 200 OK와 알림 목록 및 미읽음 개수를 반환한다")
    void getNotificationsSuccess() throws Exception {
        // given
        NotificationResponse item
            = new NotificationResponse(
                "1024",
                NotificationType.SUBMISSION_JUDGED,
                "채점 완료",
                "1001번 문제 채점이 완료되었습니다.",
                "/submissions/1",
                false,
                LocalDateTime.of(2026, 10, 21, 20, 0, 0)
            );
        NotificationPageResponse pageResponse = new NotificationPageResponse(1L, List.of(item), 0, 20, 1L, 1, false);

        given(notificationService.getNotifications(eq(1L), eq(false), any())).willReturn(pageResponse);

        // when & then
        mockMvc
            .perform(
                get("/api/v1/notifications")
                    .header("X-User-Id", "1")
                    .param("page", "0")
                    .param("size", "20")
                    .accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.data.unreadCount").value(1))
            .andExpect(jsonPath("$.data.content[0].notificationId").value("1024"))
            .andExpect(jsonPath("$.data.content[0].type").value("SUBMISSION_JUDGED"))
            .andExpect(jsonPath("$.data.content[0].title").value("채점 완료"))
            .andExpect(jsonPath("$.data.content[0].content").value("1001번 문제 채점이 완료되었습니다."))
            .andExpect(jsonPath("$.data.content[0].linkUrl").value("/submissions/1"))
            .andExpect(jsonPath("$.data.content[0].isRead").value(false))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(notificationService).getNotifications(eq(1L), eq(false), any());
    }

    @Test
    @DisplayName("7.1.2 unreadOnly=true 전달 시 미읽음 조건으로 서비스를 호출한다")
    void getNotificationsUnreadOnly() throws Exception {
        // given
        NotificationPageResponse emptyResponse = new NotificationPageResponse(0L, List.of(), 0, 20, 0L, 0, false);
        given(notificationService.getNotifications(eq(1L), eq(true), any())).willReturn(emptyResponse);

        // when & then
        mockMvc
            .perform(get("/api/v1/notifications").header("X-User-Id", "1").param("unreadOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.unreadCount").value(0))
            .andExpect(jsonPath("$.data.content").isEmpty());

        verify(notificationService).getNotifications(eq(1L), eq(true), any());
    }

    @Test
    @DisplayName("7.1.2 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED와 AUTH_TOKEN_INVALID를 반환한다")
    void getNotificationsWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("7.1.3 미읽음 알림 개수 조회 요청 시 200 OK와 unreadCount를 반환한다")
    void getUnreadCountSuccess() throws Exception {
        // given
        given(notificationService.getUnreadCount(eq(1L))).willReturn(new UnreadCountResponse(5L));

        // when & then
        mockMvc
            .perform(
                get("/api/v1/notifications/unread-count").header("X-User-Id", "1").accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.unreadCount").value(5));

        verify(notificationService).getUnreadCount(eq(1L));
    }

    @Test
    @DisplayName("7.1.3 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED와 AUTH_TOKEN_INVALID를 반환한다")
    void getUnreadCountWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("7.1.2 size가 100을 초과하면 400 BAD_REQUEST와 COMMON_INVALID_REQUEST를 반환한다")
    void getNotificationsWithExcessiveSizeReturnsBadRequest() throws Exception {
        // given
        given(notificationService.getNotifications(eq(1L), eq(false), any()))
            .willThrow(new NotificationException(CommonErrorCode.COMMON_INVALID_REQUEST));

        // when & then
        mockMvc
            .perform(get("/api/v1/notifications").header("X-User-Id", "1").param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("7.1.4 알림 단건 읽음 처리 요청 시 200 OK와 성공 메시지 및 잔여 미읽음 개수를 반환한다")
    void markAsReadSuccess() throws Exception {
        // given
        given(notificationService.markAsRead(eq(1L), eq(100L))).willReturn(new UnreadCountResponse(0L));

        // when & then
        mockMvc
            .perform(
                patch("/api/v1/notifications/100/read").header("X-User-Id", "1").accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("알림이 읽음 처리되었습니다."))
            .andExpect(jsonPath("$.data.unreadCount").value(0));

        verify(notificationService).markAsRead(eq(1L), eq(100L));
    }

    @Test
    @DisplayName("7.1.4 단건 읽음 처리 시 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED를 반환한다")
    void markAsReadWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(patch("/api/v1/notifications/100/read"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("7.1.5 알림 전체 읽음 처리 요청 시 200 OK와 성공 메시지 및 처리 건수를 반환한다")
    void markAllAsReadSuccess() throws Exception {
        // given
        given(notificationService.markAllAsRead(eq(1L))).willReturn(new NotificationReadAllResponse(5L, 0L));

        // when & then
        mockMvc
            .perform(
                patch("/api/v1/notifications/read-all").header("X-User-Id", "1").accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("모든 알림을 읽음 처리했습니다."))
            .andExpect(jsonPath("$.data.updatedCount").value(5))
            .andExpect(jsonPath("$.data.unreadCount").value(0));

        verify(notificationService).markAllAsRead(eq(1L));
    }

    @Test
    @DisplayName("7.1.5 전체 읽음 처리 시 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED를 반환한다")
    void markAllAsReadWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(patch("/api/v1/notifications/read-all"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }
}

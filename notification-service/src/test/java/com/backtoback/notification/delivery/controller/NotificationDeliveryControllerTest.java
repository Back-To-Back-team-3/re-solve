package com.backtoback.notification.delivery.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.notification.delivery.service.NotificationDeliveryService;

class NotificationDeliveryControllerTest {

    private MockMvc mockMvc;
    private NotificationDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = mock(NotificationDeliveryService.class);
        NotificationDeliveryController controller = new NotificationDeliveryController(deliveryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("7.1.1 구독 요청 시 SSE 스트림 및 프록시 버퍼링 방지 헤더와 함께 200 OK를 반환한다")
    void subscribeSuccess() throws Exception {
        // given
        given(deliveryService.subscribe(any(), any())).willReturn(new SseEmitter());

        // when & then
        mockMvc
            .perform(
                get("/api/v1/notifications/subscribe")
                    .header("X-User-Id", "1")
                    .header("Last-Event-ID", "1024")
                    .accept(MediaType.TEXT_EVENT_STREAM)
            )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE))
            .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
            .andExpect(header().string("Connection", "keep-alive"))
            .andExpect(header().string("X-Accel-Buffering", "no"));

        verify(deliveryService).subscribe(1L, "1024");
    }

    @Test
    @DisplayName("7.1.1 X-User-Id 헤더가 누락되면 401 UNAUTHORIZED와 AUTH_TOKEN_INVALID를 반환한다")
    void subscribeWithoutUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(get("/api/v1/notifications/subscribe"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("7.1.1 X-User-Id 헤더가 숫자가 아니면 401 UNAUTHORIZED와 AUTH_TOKEN_INVALID를 반환한다")
    void subscribeWithInvalidUserIdHeaderReturnsUnauthorized() throws Exception {
        // when & then
        mockMvc
            .perform(get("/api/v1/notifications/subscribe").header("X-User-Id", "abc"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }
}

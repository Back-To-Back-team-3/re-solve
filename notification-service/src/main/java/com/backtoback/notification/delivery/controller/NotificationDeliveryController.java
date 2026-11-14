package com.backtoback.notification.delivery.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.notification.delivery.service.NotificationDeliveryService;

import lombok.RequiredArgsConstructor;

/**
 * 실시간 알림 스트림(SSE) 구독 엔드포인트 컨트롤러 (API 명세서 7.1.1).
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationDeliveryController {

    private final NotificationDeliveryService deliveryService;

    /**
     * 7.1.1 실시간 알림 스트림 (SSE) 구독.
     * 클라이언트와 SSE 스트림 연결을 수립하고 실시간 알림 및 하트비트를 전송한다.
     *
     * @param memberId Gateway가 주입한 사용자 식별자
     * @param lastEventId 클라이언트가 마지막으로 수신한 이벤트 식별자 (재연결 시 전달)
     * @return SseEmitter 실시간 스트림 응답
     */
    @GetMapping(
        value = "/subscribe",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<SseEmitter> subscribe(
        @RequestHeader(name = "X-User-Id") Long memberId,
        @RequestHeader(
            name = "Last-Event-ID",
            required = false
        ) String lastEventId
    ) {
        SseEmitter emitter = deliveryService.subscribe(memberId, lastEventId);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        headers.set(HttpHeaders.CONNECTION, "keep-alive");
        headers.set("X-Accel-Buffering", "no");

        return ResponseEntity.ok().headers(headers).body(emitter);
    }
}

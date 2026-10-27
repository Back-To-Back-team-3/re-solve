package com.backtoback.notification.delivery.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backtoback.notification.delivery.service.NotificationDeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 알림 스트림의 30초 주기 Heartbeat 전송 및 60초 경과 연결 세션의 보조 정리(Sweeper)를 담당하는 스케줄러.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryScheduler {

    private final NotificationDeliveryService deliveryService;

    /**
     * 30초마다 활성화된 모든 Emitter에 Heartbeat 이벤트를 전송하여 프록시 유휴 타임아웃을 회피한다.
     */
    @Scheduled(fixedRate = 30_000)
    public void sendHeartbeat() {
        log.trace("SSE 하트비트 브로드캐스트 시작");
        deliveryService.broadcastHeartbeat();
    }

    /**
     * 10초 지연마다 60초 이상 유지된 Emitter 세션을 정상 완료(complete) 처리하고 저장소에서 제거한다.
     * 기본 타임아웃(60초)은 서블릿 컨테이너의 SseEmitter 비동기 타임아웃이 먼저 처리하며,
     * 이 스케줄러는 컨테이너 타임아웃 콜백 지연·미발화 시 인메모리 세션이 잔존하지 않도록 보장하는 보조 스위퍼 역할을 한다.
     * 두 경로가 동시에 겹치더라도 세션 동기화 및 예외 무시를 통해 안전하게 멱등 정리된다 (스케줄러 0.1, 7.0).
     */
    @Scheduled(fixedDelay = 10_000)
    public void closeExpiredConnections() {
        deliveryService.closeExpiredConnections(NotificationDeliveryService.SSE_TIMEOUT_MILLIS);
    }
}

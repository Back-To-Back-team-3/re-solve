package com.backtoback.notification.delivery.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backtoback.notification.delivery.service.NotificationDeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 알림 스트림의 30초 주기 Heartbeat 전송 및 60초 경과 연결 세션의 정상 종료를 담당하는 스케줄러.
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
     * 10초 지연마다 60초 이상 유지된 Emitter 세션을 정상 완료(complete) 처리하여 클라이언트의 재연결을 유도한다.
     * 이전 회차 작업 완료 후 10초 대기하여 대규모 세션 순회 시 스레드 중첩 실행을 방지한다 (스케줄러 0.1, 7.0).
     */
    @Scheduled(fixedDelay = 10_000)
    public void closeExpiredConnections() {
        deliveryService.closeExpiredConnections(NotificationDeliveryService.SSE_TIMEOUT_MILLIS);
    }
}

package com.backtoback.integration.infra.event.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backtoback.integration.infra.event.service.OutboxRelayService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 1초 주기로 Outbox 미발행 이벤트를 선점 발행하는 스케줄러 (scheduler.md > 0.2.1).
 * ShedLock 없이 SKIP LOCKED를 사용하여 다중 인스턴스가 안전하게 병렬 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxRelayService outboxRelayService;

    @Scheduled(
        fixedDelayString = "${infra.outbox.relay-fixed-delay-ms:1000}",
        initialDelayString = "${infra.outbox.relay-initial-delay-ms:5000}"
    )
    public void runRelay() {
        try {
            int processed = outboxRelayService.relayPendingEvents();
            if (processed > 0) {
                log.debug("OutboxRelay 실행 완료: {}건 처리", processed);
            }
        } catch (Exception exception) {
            log.error("OutboxRelay 회차 실행 중 예기치 않은 오류 발생", exception);
        }
    }
}

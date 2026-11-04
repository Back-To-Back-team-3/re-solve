package com.backtoback.integration.infra.metrics;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backtoback.integration.infra.event.domain.OutboxEventStatus;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 미발행(PENDING) 이벤트 건수를 주기적으로 갱신하여 Prometheus 게이지로 노출한다.
 * 스크랩마다 COUNT 쿼리가 실행되지 않도록 메모리 값을 읽는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMetricsBinder implements MeterBinder {

    private final OutboxEventRepository outboxEventRepository;
    private final AtomicLong pendingCount = new AtomicLong(0);

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge
            .builder("outbox.pending.count", pendingCount, AtomicLong::get)
            .description("Number of pending outbox events awaiting relay")
            .register(registry);
    }

    /**
     * PENDING 상태 Outbox 이벤트 건수를 조회하여 게이지 값을 갱신한다.
     */
    @Scheduled(
        fixedDelayString = "${integration.metrics.outbox.refresh-interval-ms:10000}",
        initialDelay = 5000
    )
    public void refreshPendingCount() {
        try {
            pendingCount.set(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING));
        } catch (Exception e) {
            log.warn("Outbox PENDING 건수 조회 실패: error={}", e.getMessage());
        }
    }
}

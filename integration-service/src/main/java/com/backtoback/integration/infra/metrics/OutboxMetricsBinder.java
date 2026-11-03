package com.backtoback.integration.infra.metrics;

import org.springframework.stereotype.Component;

import com.backtoback.integration.infra.event.domain.OutboxEventStatus;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

/**
 * Outbox 발행 대기(PENDING) 적체 건수를 Prometheus 게이지 메트릭으로 등록한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxMetricsBinder implements MeterBinder {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge
            .builder(
                "outbox.pending.count",
                outboxEventRepository,
                repo -> repo.countByStatus(OutboxEventStatus.PENDING)
            )
            .description("Number of pending outbox events awaiting relay")
            .register(registry);
    }
}

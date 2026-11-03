package com.backtoback.integration.infra.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.integration.infra.event.domain.OutboxEventStatus;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class OutboxMetricsBinderTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("OutboxMetricsBinder는 outbox.pending.count 게이지를 정상 등록하고 PENDING 건수를 반환한다")
    void shouldRegisterPendingCountGauge() {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();
        given(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).willReturn(42L);

        OutboxMetricsBinder binder = new OutboxMetricsBinder(outboxEventRepository);

        // when
        binder.bindTo(registry);

        // then
        Double count = registry.get("outbox.pending.count").gauge().value();
        assertThat(count).isEqualTo(42.0);
    }
}

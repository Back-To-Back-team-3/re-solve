package com.backtoback.integration.infra.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

/**
 * GitHub API Rate Limit 잔여량을 Prometheus 게이지 메트릭으로 등록한다.
 */
@Component
@RequiredArgsConstructor
public class GithubRateLimitMetricsBinder implements MeterBinder {

    private final GithubRateLimitTracker tracker;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge
            .builder("github.ratelimit.remaining", tracker, t -> t.isObserved() ? t.getRemaining() : Double.NaN)
            .description("Remaining GitHub REST API rate limit quota")
            .register(registry);
    }
}

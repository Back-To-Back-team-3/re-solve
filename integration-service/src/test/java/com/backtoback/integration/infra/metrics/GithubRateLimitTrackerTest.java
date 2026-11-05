package com.backtoback.integration.infra.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GithubRateLimitTrackerTest {

    @Test
    @DisplayName("관측 전에는 미관측 상태이고 수동 update 호출 시 갱신된다")
    void shouldStartUnobservedAndAllowUpdate() {
        GithubRateLimitTracker tracker = new GithubRateLimitTracker();
        assertThat(tracker.isObserved()).isFalse();

        Instant resetTime = Instant.now().plusSeconds(3600);
        tracker.update(4500L, resetTime);

        assertThat(tracker.isObserved()).isTrue();
        assertThat(tracker.getRemaining()).isEqualTo(4500L);
        assertThat(tracker.getResetAt()).isEqualTo(resetTime);
    }

    @Test
    @DisplayName("동일한 리셋 윈도우 내에서는 더 작은 잔여량만 유지한다")
    void shouldKeepLowerRemainingWithinSameResetWindow() {
        GithubRateLimitTracker tracker = new GithubRateLimitTracker();
        Instant resetTime = Instant.now().plusSeconds(1800);

        tracker.update(3000L, resetTime);
        tracker.update(3500L, resetTime); // 높은 값 도착 (지연 응답 가정)
        assertThat(tracker.getRemaining()).isEqualTo(3000L);

        tracker.update(2500L, resetTime); // 낮은 값 도착
        assertThat(tracker.getRemaining()).isEqualTo(2500L);
    }

    @Test
    @DisplayName("더 미래의 리셋 윈도우가 오면 새 잔여량과 리셋 시각으로 갱신된다")
    void shouldUpdateOnNewerResetWindow() {
        GithubRateLimitTracker tracker = new GithubRateLimitTracker();
        Instant resetTime1 = Instant.now().plusSeconds(100);
        Instant resetTime2 = Instant.now().plusSeconds(3600);

        tracker.update(100L, resetTime1);
        tracker.update(5000L, resetTime2); // 새 윈도우 리셋

        assertThat(tracker.getRemaining()).isEqualTo(5000L);
        assertThat(tracker.getResetAt()).isEqualTo(resetTime2);
    }

    @Test
    @DisplayName("관측 전 게이지는 NaN을 반환해 임의 기본값이 게시되지 않는다")
    void shouldExposeNaNBeforeObservation() {
        GithubRateLimitMetricsBinder binder = new GithubRateLimitMetricsBinder(new GithubRateLimitTracker());
        MeterRegistry registry = new SimpleMeterRegistry();

        binder.bindTo(registry);

        assertThat(registry.get("github.ratelimit.remaining").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("HTTP 응답 헤더에서 x-ratelimit-remaining 및 x-ratelimit-reset을 정상 파싱한다")
    void shouldUpdateFromHeaders() {
        GithubRateLimitTracker tracker = new GithubRateLimitTracker();
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-remaining", "3200");
        headers.set("x-ratelimit-reset", "1700000000");

        tracker.updateFromHeaders(headers);

        assertThat(tracker.getRemaining()).isEqualTo(3200L);
        assertThat(tracker.getResetAt()).isEqualTo(Instant.ofEpochSecond(1700000000L));
    }

    @Test
    @DisplayName("헤더 파싱 실패 시 예외 없이 기존 값을 보존한다")
    void shouldPreserveValuesOnInvalidHeaders() {
        GithubRateLimitTracker tracker = new GithubRateLimitTracker();
        tracker.update(2500L, Instant.ofEpochSecond(1600000000L));

        HttpHeaders invalidHeaders = new HttpHeaders();
        invalidHeaders.set("x-ratelimit-remaining", "invalid-number");
        invalidHeaders.set("x-ratelimit-reset", "invalid-reset");

        tracker.updateFromHeaders(invalidHeaders);

        assertThat(tracker.getRemaining()).isEqualTo(2500L);
        assertThat(tracker.getResetAt()).isEqualTo(Instant.ofEpochSecond(1600000000L));
    }

    @Test
    @DisplayName("GithubRateLimitMetricsBinder는 github.ratelimit.remaining 게이지를 등록한다")
    void shouldRegisterRemainingGauge() {
        GithubRateLimitTracker tracker = new GithubRateLimitTracker();
        tracker.update(1234L, null);

        GithubRateLimitMetricsBinder binder = new GithubRateLimitMetricsBinder(tracker);
        MeterRegistry registry = new SimpleMeterRegistry();

        binder.bindTo(registry);

        Double remaining = registry.get("github.ratelimit.remaining").gauge().value();
        assertThat(remaining).isEqualTo(1234.0);
    }
}

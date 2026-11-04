package com.backtoback.integration.infra.metrics;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * GitHub API 호출 응답 헤더로부터 Rate Limit 잔여량과 리셋 시각을 추적한다.
 */
@Component
public class GithubRateLimitTracker {

    private static final String HEADER_RATE_LIMIT_REMAINING = "x-ratelimit-remaining";
    private static final String HEADER_RATE_LIMIT_RESET = "x-ratelimit-reset";
    private static final long UNOBSERVED = -1L;

    private final AtomicLong remaining = new AtomicLong(UNOBSERVED);
    private final AtomicReference<Instant> resetAt = new AtomicReference<>();

    /**
     * Rate Limit 잔여량과 리셋 시각을 직접 설정한다.
     *
     * @param remainingCount 잔여 호출 가능 횟수
     * @param resetTime 할당량 리셋 시각
     */
    public void update(long remainingCount, Instant resetTime) {
        this.remaining.set(remainingCount);
        if (resetTime != null) {
            this.resetAt.set(resetTime);
        }
    }

    /**
     * HTTP 응답 헤더에서 Rate Limit 정보를 추출하여 상태를 갱신한다.
     *
     * @param headers HTTP 응답 헤더
     */
    public void updateFromHeaders(HttpHeaders headers) {
        if (headers == null) {
            return;
        }
        String remainingHeader = headers.getFirst(HEADER_RATE_LIMIT_REMAINING);
        if (remainingHeader != null) {
            try {
                this.remaining.set(Long.parseLong(remainingHeader.trim()));
            } catch (NumberFormatException ignored) {
                // 파싱 실패 시 이전 값 유지
            }
        }
        String resetHeader = headers.getFirst(HEADER_RATE_LIMIT_RESET);
        if (resetHeader != null) {
            try {
                long epochSecond = Long.parseLong(resetHeader.trim());
                this.resetAt.set(Instant.ofEpochSecond(epochSecond));
            } catch (Exception ignored) {
                // 파싱 실패 시 이전 값 유지
            }
        }
    }

    /**
     * 현재 기록된 Rate Limit 잔여량을 반환한다.
     *
     * @return 잔여 횟수
     */
    public long getRemaining() {
        return remaining.get();
    }

    /**
     * GitHub 응답에서 잔여량을 한 번이라도 관측했는지 반환한다.
     * 재시작 직후처럼 관측 전에는 임의 기본값을 게시하지 않기 위해 사용한다.
     *
     * @return 관측했으면 true
     */
    public boolean isObserved() {
        return remaining.get() != UNOBSERVED;
    }

    /**
     * 현재 기록된 Rate Limit 리셋 시각을 반환한다.
     *
     * @return 리셋 시각
     */
    public Instant getResetAt() {
        return resetAt.get();
    }
}

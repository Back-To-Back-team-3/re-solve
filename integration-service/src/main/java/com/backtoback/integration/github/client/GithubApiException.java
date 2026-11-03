package com.backtoback.integration.github.client;

import java.time.Instant;

import lombok.Getter;

/**
 * GitHub REST API가 오류 상태 코드를 반환했을 때 던지는 예외.
 * 워커가 401(권한 만료)·403/429(Rate Limit)·409(sha 충돌)·5xx를 구분해 상태 전이를 결정하도록 상태 코드와
 * Rate Limit 초기화 시각을 함께 보관한다 (scheduler.md &gt; 8.1 상태 전이).
 */
@Getter
public class GithubApiException extends RuntimeException {

    private final int statusCode;
    private final Instant rateLimitResetAt;

    public GithubApiException(String message, int statusCode, Instant rateLimitResetAt, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.rateLimitResetAt = rateLimitResetAt;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    public boolean isRateLimited() {
        return statusCode == 403 || statusCode == 429;
    }

    public boolean isConflict() {
        return statusCode == 409;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }
}

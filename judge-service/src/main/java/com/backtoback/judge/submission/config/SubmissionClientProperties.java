package com.backtoback.judge.submission.config;

import java.net.URI;
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * 접수 사전 검증에 사용하는 내부 HTTP 설정이다. (API 명세서 §1.8·§3.1.2)
 *
 * @param problemBaseUrl Problem 내부 주소
 * @param memberBaseUrl Member 내부 주소
 * @param timeout 연결·응답 제한 시간
 */
@Validated
@ConfigurationProperties("judge.clients")
public record SubmissionClientProperties(
    @NotNull @DefaultValue("http://problem-service:8080") URI problemBaseUrl,
    @NotNull @DefaultValue("http://member-service:8080") URI memberBaseUrl,
    @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE) @DefaultValue("2s") Duration timeout
) {
}

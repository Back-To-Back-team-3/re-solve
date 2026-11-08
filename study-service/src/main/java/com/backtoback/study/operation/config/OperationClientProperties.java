package com.backtoback.study.operation.config;

import java.net.URI;
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * 스터디 운영 외부 API 설정 (API 명세서 §5.3.4).
 *
 * @param contestBaseUrl contest 서비스 내부 주소
 * @param timeout 연결·응답 제한 시간
 */
@Validated
@ConfigurationProperties("study.operation.clients")
public record OperationClientProperties(
    @NotNull @DefaultValue("http://contest-service:8080") URI contestBaseUrl,
    @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE) @DefaultValue("2s") Duration timeout
) {

}

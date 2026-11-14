package com.backtoback.study.assignment.config;

import java.net.URI;
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * 문제집 생성 시 problem 서비스 내부 API 설정 (API 명세서 §5.4.1).
 *
 * @param problemBaseUrl problem 서비스 내부 주소
 * @param timeout 연결·응답 제한 시간
 */
@Validated
@ConfigurationProperties("study.clients")
public record AssignmentClientProperties(
    @NotNull @DefaultValue("http://problem-service:8080") URI problemBaseUrl,
    @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE) @DefaultValue("2s") Duration timeout
) {

}

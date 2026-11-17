package com.backtoback.study.member.config;

import java.net.URI;
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * member 서비스 내부 상태 조회 설정 (API 명세서 1.7.1).
 *
 * @param baseUrl member 서비스 내부 주소
 * @param timeout 연결·응답 제한 시간
 */
@Validated
@ConfigurationProperties("study.member-client")
public record MemberClientProperties(
    @NotNull @DefaultValue("http://member-service:8080") URI baseUrl,
    @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE) @DefaultValue("2s") Duration timeout
) {

}

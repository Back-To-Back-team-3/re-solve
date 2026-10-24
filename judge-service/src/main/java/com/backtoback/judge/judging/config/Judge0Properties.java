package com.backtoback.judge.judging.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Judge0 통신 설정. 기본값은 {@code application.yaml}에서 관리한다.
 *
 * @param baseUrl Judge0 서버 주소; 미설정이면 빈 문자열
 * @param authHeader 인증 헤더 이름
 * @param authToken 인증 토큰; 빈 값이면 헤더를 추가하지 않는다
 * @param connectTimeout 연결 제한 시간; 1ms 이상 int 밀리초 범위
 * @param readTimeout 응답 제한 시간; 1ms 이상 int 밀리초 범위
 */
@Validated
@ConfigurationProperties(prefix = "judge0")
public record Judge0Properties(
    String baseUrl,
    String authHeader,
    String authToken,
    @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE) Duration connectTimeout,
    @NotNull @DurationMin(millis = 1) @DurationMax(millis = Integer.MAX_VALUE) Duration readTimeout
) {
}

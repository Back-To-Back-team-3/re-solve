package com.backtoback.contest.exam.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * 회원 서비스 내부 주소다.
 */
@ConfigurationProperties(prefix = "contest.member")
@Validated
public record MemberClientProperties(@NotBlank String baseUrl) {
}

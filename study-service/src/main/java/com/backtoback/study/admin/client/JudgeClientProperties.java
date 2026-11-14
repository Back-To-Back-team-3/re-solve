package com.backtoback.study.admin.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** judge-service 클라이언트 설정 (study.judge-client). */
@ConfigurationProperties(prefix = "study.judge-client")
@Validated
public record JudgeClientProperties(@NotBlank String baseUrl) {
}

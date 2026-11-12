package com.backtoback.ai.hint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties("ai.study")
public record StudyClientProperties(@NotBlank String baseUrl) {
}

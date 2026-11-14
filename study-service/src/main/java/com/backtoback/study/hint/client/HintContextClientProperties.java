package com.backtoback.study.hint.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** hint-context 클라이언트 설정 (study.hint-context-client). */
@ConfigurationProperties(prefix = "study.hint-context-client")
@Validated
public record HintContextClientProperties(@NotBlank String problemBaseUrl) {
}

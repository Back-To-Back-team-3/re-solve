package com.backtoback.problem.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 발행기가 사용할 기존 Standard 큐 주소를 검증한다.
 *
 * @param validationQueueUrl 검증 요청 큐 URL
 */
@Validated
@ConfigurationProperties("problem.messaging")
public record ProblemMessagingProperties(
    @NotBlank(message = "검증 큐 URL은 필수입니다.")
    @Pattern(
        regexp = "https?://[^\\s]+(?<!\\.fifo)",
        message = "Standard SQS 큐 URL이어야 합니다."
    ) String validationQueueUrl
) {
}

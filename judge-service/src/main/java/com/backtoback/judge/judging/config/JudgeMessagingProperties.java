package com.backtoback.judge.judging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 명시적으로 활성화한 Judge 메시지 전달의 리소스 설정이다.
 *
 * @param topicArn Judge 결과용 Standard SNS 토픽 ARN
 * @param validationQueueUrl 관리자 검증용 Standard SQS 큐 URL
 */
@Validated
@ConfigurationProperties("judge.messaging")
public record JudgeMessagingProperties(
    @NotBlank @Pattern(regexp = "arn:[^:]+:sns:[^:]+:[0-9]{12}:[A-Za-z0-9_-]+") String topicArn,
    @NotBlank @Pattern(regexp = "https?://[^\\s]+(?<!\\.fifo)") String validationQueueUrl
) {
}

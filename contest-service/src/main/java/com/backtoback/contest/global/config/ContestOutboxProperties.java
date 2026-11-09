package com.backtoback.contest.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** Contest Outbox 발행 대상인 SNS 토픽 설정. */
@Validated
@ConfigurationProperties(prefix = "contest.events")
public record ContestOutboxProperties(
    @NotBlank(message = "Contest 이벤트 토픽 ARN이 필요합니다.") String topicArn,
    @NotBlank(message = "Judge 제출 큐 URL이 필요합니다.") String submissionQueueUrl
) {
}

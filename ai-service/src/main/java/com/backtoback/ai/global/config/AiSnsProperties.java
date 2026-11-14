package com.backtoback.ai.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * ai-service SNS 발행 설정 (ai.sns.topic-arn).
 */
@Validated
@ConfigurationProperties("ai.sns")
public record AiSnsProperties(String topicArn) {

}

package com.backtoback.member.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회원 이벤트 SNS 발행 설정 (이벤트 계약 2.1 {@code member-events}).
 *
 * @param enabled 켜면 Outbox Relay가 기록된 이벤트를 SNS로 발행한다.
 * @param topicArn {@code resolve-{env}-member-events} 토픽 ARN. 켜져 있으면 필수다.
 */
@ConfigurationProperties(prefix = "resolve.messaging")
public record MessagingProperties(boolean enabled, String topicArn) {

}

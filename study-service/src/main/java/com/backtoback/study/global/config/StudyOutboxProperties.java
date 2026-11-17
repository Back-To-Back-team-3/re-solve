package com.backtoback.study.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * Study Outbox 이벤트 발행 설정.
 *
 * @param publishEnabled 공통 Outbox Relay 등록 여부
 * @param topicArn Study 이벤트를 전달할 {@code study-events} SNS 토픽 ARN
 */
@Validated
@ConfigurationProperties(prefix = "study.events")
public record StudyOutboxProperties(boolean publishEnabled, String topicArn) {
    /**
     * 발행을 켠 경우에만 토픽 ARN을 요구한다.
     * 설정 클래스 스캔으로 발행을 끈 환경에서도 바인딩되므로 필드 단위 필수 검증을 쓰지 않는다.
     *
     * @return 발행을 껐거나 토픽 ARN이 있으면 {@code true}
     */
    @AssertTrue(message = "Study 이벤트 발행을 켜면 토픽 ARN이 필요합니다.")
    public boolean isTopicConfigured() {
        return !publishEnabled || StringUtils.hasText(topicArn);
    }
}

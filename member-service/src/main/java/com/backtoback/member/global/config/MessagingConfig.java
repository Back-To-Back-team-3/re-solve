package com.backtoback.member.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.Assert;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;

/**
 * Outbox에 기록한 회원 이벤트를 SNS {@code member-events} 토픽으로 발행한다. {@code resolve.messaging.enabled}가 켜진 환경에서만 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("resolve.messaging.enabled")
@Import(OutboxRelayConfiguration.class)
public class MessagingConfig {

    @Bean
    public EventDestinationResolver memberEventDestinationResolver(MessagingProperties properties) {
        Assert.hasText(properties.topicArn(), "MEMBER_EVENTS_TOPIC_ARN이 필요합니다.");
        EventDestination destination = new EventDestination(EventDestination.Channel.SNS, properties.topicArn());
        return event -> destination;
    }
}

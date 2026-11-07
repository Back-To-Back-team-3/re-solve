package com.backtoback.contest.global.config;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import software.amazon.awssdk.services.sns.SnsClient;

/** Contest의 시험 설정·참가 이벤트를 공통 Relay로 발행한다 (이벤트 계약서 §3, §4.4). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("contest.events.publish-enabled")
@EnableConfigurationProperties(ContestOutboxProperties.class)
@Import(OutboxRelayConfiguration.class)
public class ContestOutboxConfiguration {
    private static final Set<String> TOPIC_EVENTS
        = Set
            .of(
                "ExamUpdated",
                "ExamParticipantRegistered",
                "ExamParticipantCanceled",
                "ExamStarted",
                "ExamClosed",
                "ExamCanceled"
            );

    @Bean
    SnsClient contestSnsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SnsClient.builder()).build();
    }

    @Bean
    EventDestinationResolver contestEventDestinationResolver(ContestOutboxProperties properties) {
        var destination = new EventDestination(EventDestination.Channel.SNS, properties.topicArn());
        return event -> {
            var metadata = event.metadata();
            if (!"v1".equals(metadata.schemaVersion()) || !TOPIC_EVENTS.contains(metadata.eventType())) {
                throw new IllegalArgumentException("지원하지 않는 Contest 발행 이벤트입니다.");
            }
            return destination;
        };
    }
}

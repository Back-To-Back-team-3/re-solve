package com.backtoback.member.global.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * {@code member-queue} 수신 설정. 소비 트랜잭션 커밋 후 SQS 삭제가 끝날 때까지 기다리도록 즉시 ACK한다(contest와 같음).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("resolve.messaging.consumer-enabled")
public class EventListenerConfig {

    @Bean
    SqsMessageListenerContainerFactory<Object> memberSqsListenerFactory(SqsAsyncClient client) {
        return SqsMessageListenerContainerFactory
            .builder()
            .sqsAsyncClient(client)
            .configure(options -> options.acknowledgementInterval(Duration.ZERO).acknowledgementThreshold(0))
            .build();
    }
}

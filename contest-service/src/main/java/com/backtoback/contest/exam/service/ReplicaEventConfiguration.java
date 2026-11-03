package com.backtoback.contest.exam.service;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * 소비 커밋 이후 실제 SQS 삭제 완료를 기다리도록 즉시 ACK를 구성한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "contest.events",
    name = "enabled",
    havingValue = "true"
)
public class ReplicaEventConfiguration {
    @Bean
    SqsMessageListenerContainerFactory<Object> contestSqsListenerFactory(SqsAsyncClient client) {
        return SqsMessageListenerContainerFactory
            .builder()
            .sqsAsyncClient(client)
            .configure(options -> options.acknowledgementInterval(Duration.ZERO).acknowledgementThreshold(0))
            .build();
    }
}

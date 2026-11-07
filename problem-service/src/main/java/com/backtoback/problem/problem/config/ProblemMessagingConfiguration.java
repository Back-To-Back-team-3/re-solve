package com.backtoback.problem.problem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import software.amazon.awssdk.services.sqs.SqsClient;

/** 공통 Outbox Relay로 검증 요청을 발행한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProblemMessagingProperties.class)
@Import(OutboxRelayConfiguration.class)
public class ProblemMessagingConfiguration {
    /**
     * 기존 AWS 리전·자격 증명·엔드포인트로 동기 SQS 발행 클라이언트를 만든다.
     *
     * @param configurer 기존 AWS 공통 설정
     * @return 발행 전용 클라이언트
     */
    @Bean
    public SqsClient problemSqsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SqsClient.builder()).build();
    }

    /**
     * 지원하는 검증 요청만 기존 큐로 전달한다.
     *
     * @param properties 검증된 큐 설정
     * @return 발행 목적지 선택기
     */
    @Bean
    public EventDestinationResolver problemEventDestinationResolver(ProblemMessagingProperties properties) {
        return event -> {
            if (!"ProblemValidationRequested".equals(event.metadata().eventType())
                || !"v1".equals(event.metadata().schemaVersion())) {
                throw new IllegalArgumentException("지원하지 않는 Problem 발행 이벤트입니다.");
            }
            return new EventDestination(EventDestination.Channel.SQS, properties.validationQueueUrl());
        };
    }
}

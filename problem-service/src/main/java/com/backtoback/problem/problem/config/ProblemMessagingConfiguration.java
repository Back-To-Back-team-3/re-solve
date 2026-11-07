package com.backtoback.problem.problem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.EventConsumerConfiguration;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** 공통 Outbox Relay로 검증 요청을 발행한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    {
        ProblemMessagingProperties.class,
        ProblemIntegrationProperties.class
    }
)
@Import(
    {
        OutboxRelayConfiguration.class,
        EventConsumerConfiguration.class
    }
)
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
     * 검증 요청은 검증 큐로, 사용 정보 변경은 문제 토픽으로 전달한다.
     *
     * @param properties 검증된 큐 설정
     * @param integration 문제 상태 발행 목적지
     * @return 발행 목적지 선택기
     */
    @Bean
    public EventDestinationResolver problemEventDestinationResolver(
        ProblemMessagingProperties properties,
        ProblemIntegrationProperties integration
    ) {
        return event -> {
            if ("ProblemStateChanged".equals(event.metadata().eventType())
                && "v2".equals(event.metadata().schemaVersion())) {
                String topic = integration.topicArn();
                if (topic == null || topic.isBlank()) {
                    throw new IllegalStateException("문제 상태 이벤트 발행 목적지가 필요합니다.");
                }
                return new EventDestination(EventDestination.Channel.SNS, topic);
            }
            if (!"ProblemValidationRequested".equals(event.metadata().eventType())
                || !"v1".equals(event.metadata().schemaVersion())) {
                throw new IllegalArgumentException("지원하지 않는 Problem 발행 이벤트입니다.");
            }
            return new EventDestination(EventDestination.Channel.SQS, properties.validationQueueUrl());
        };
    }

    /**
     * 문제 상태 변경을 전송할 SNS 클라이언트를 구성한다.
     *
     * @param configurer 서비스 AWS 설정
     * @return SNS 클라이언트
     */
    @Bean
    public SnsClient problemSnsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SnsClient.builder()).build();
    }

}

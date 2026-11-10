package com.backtoback.member.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.Assert;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;
import com.backtoback.member.member.service.MemberEventPublisher;

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Outbox에 기록한 회원 이벤트를 발행한다. {@code resolve.messaging.enabled}가 켜진 환경에서만 등록한다.
 * 진단 채점 요청({@code DiagnosisSubmissionRequested}, M6)은 SQS {@code exam-submission-queue}로, 나머지는 SNS
 * {@code member-events} 토픽으로 보낸다(이벤트 계약 2.1·2.2).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("resolve.messaging.enabled")
@Import(OutboxRelayConfiguration.class)
public class MessagingConfig {

    @Bean
    public EventDestinationResolver memberEventDestinationResolver(MessagingProperties properties) {
        Assert.hasText(properties.topicArn(), "MEMBER_EVENTS_TOPIC_ARN이 필요합니다.");
        Assert.hasText(properties.examSubmissionQueueUrl(), "EXAM_SUBMISSION_QUEUE_URL이 필요합니다.");
        EventDestination topic = new EventDestination(EventDestination.Channel.SNS, properties.topicArn());
        EventDestination examSubmissionQueue
            = new EventDestination(EventDestination.Channel.SQS, properties.examSubmissionQueueUrl());
        return event -> MemberEventPublisher.DIAGNOSIS_SUBMISSION_REQUESTED.equals(event.metadata().eventType())
            ? examSubmissionQueue : topic;
    }

    /**
     * Relay의 SQS 직접 전달용 동기 클라이언트. 수신 리스너는 별도의 {@code SqsAsyncClient}를 쓴다.
     */
    @Bean
    public SqsClient memberSqsClient(AwsClientBuilderConfigurer configurer) {
        return configurer.configure(SqsClient.builder()).build();
    }
}

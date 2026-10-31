package com.backtoback.judge.judging.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.config.OutboxRelayConfiguration;
import com.backtoback.common.event.dto.EventDestination;
import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.judge.judging.service.ValidationMessageListener;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** 준비된 AWS 리소스가 있을 때만 검증 수신과 결과 SNS Relay를 활성화한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "judge.messaging.enabled",
    havingValue = "true"
)
@EnableConfigurationProperties(JudgeMessagingProperties.class)
@Import(OutboxRelayConfiguration.class)
public class JudgeMessagingConfiguration {
    private static final Set<String> RESULT_EVENTS
        = Set
            .of(
                "ProblemValidationCompleted",
                "SubmissionJudged",
                "ExamSubmissionJudged",
                "ContestSubmissionJudged",
                "SubmissionFailed"
            );

    /**
     * 확정된 Judge 결과 이벤트만 서비스의 Standard SNS 토픽으로 보낸다.
     *
     * @param properties 검증한 리소스 주소
     * @return 이벤트 목적지 선택기
     */
    @Bean
    public EventDestinationResolver judgeEventDestinationResolver(JudgeMessagingProperties properties) {
        return event -> {
            if (!RESULT_EVENTS.contains(event.metadata().eventType())
                || !"v1".equals(event.metadata().schemaVersion())) {
                throw new IllegalArgumentException("지원하지 않는 Judge 결과 이벤트입니다.");
            }
            return new EventDestination(EventDestination.Channel.SNS, properties.topicArn());
        };
    }

    /**
     * ACK 배치를 끄고 삭제 완료를 기다리며 없는 큐를 자동 생성하지 않는다.
     *
     * @param client 기존 리전·자격 증명·엔드포인트를 사용하는 SQS 클라이언트
     * @return 검증 리스너 전용 팩토리
     */
    @Bean
    public SqsMessageListenerContainerFactory<Object> judgeValidationListenerFactory(SqsAsyncClient client) {
        return SqsMessageListenerContainerFactory
            .builder()
            .sqsAsyncClient(client)
            .configure(
                options -> options
                    .acknowledgementInterval(Duration.ZERO)
                    .acknowledgementThreshold(0)
                    .queueNotFoundStrategy(QueueNotFoundStrategy.FAIL)
            )
            .build();
    }

    /**
     * 활성화된 환경에서만 수동 ACK 리스너를 등록한다.
     *
     * @param consumer Judge 업무 DB의 공통 소비기
     * @return 검증 요청 리스너
     */
    @Bean
    public ValidationMessageListener validationMessageListener(EventConsumer consumer) {
        return new ValidationMessageListener(consumer);
    }
}

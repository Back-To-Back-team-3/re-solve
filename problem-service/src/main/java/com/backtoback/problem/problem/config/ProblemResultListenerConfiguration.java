package com.backtoback.problem.problem.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.event.service.EventMessageDecoder;
import com.backtoback.problem.problem.service.ValidationResultListener;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Problem 수신 큐가 구성되면 결과 소비와 커밋 후 수동 ACK를 연결한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!environment.getProperty('PROBLEM_QUEUE_URL', '').isBlank()")
public class ProblemResultListenerConfiguration {
    /**
     * ACK 배치를 끄고 삭제 완료를 기다리며 없는 큐를 자동 생성하지 않는다.
     *
     * @param client 기존 리전·자격 증명·엔드포인트를 사용하는 SQS 클라이언트
     * @return 검증 리스너 전용 팩토리
     */
    @Bean
    public SqsMessageListenerContainerFactory<Object> problemResultListenerFactory(SqsAsyncClient client) {
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
     * @param consumer Problem 업무 DB의 공통 소비기
     * @param decoder 공통 이벤트 해석기
     * @param handlers 등록된 처리기
     * @param properties 승인된 상태 이벤트 발행 목적지
     * @return 검증 결과 리스너
     */
    @Bean
    public ValidationResultListener validationMessageListener(
        EventConsumer consumer,
        EventMessageDecoder decoder,
        List<EventHandler> handlers,
        ProblemIntegrationProperties properties
    ) {
        if (properties.topicArn() == null || properties.topicArn().isBlank()) {
            throw new IllegalArgumentException("검증 결과 수신에는 문제 상태 SNS 토픽 ARN이 필요합니다.");
        }
        return new ValidationResultListener(consumer, decoder, handlers);
    }

}

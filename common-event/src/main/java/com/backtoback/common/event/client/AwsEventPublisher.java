package com.backtoback.common.event.client;

import java.time.Duration;
import java.util.Map;

import com.backtoback.common.event.dto.OutboxEvent;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * 저장된 이벤트 JSON을 서비스가 선택한 SNS 토픽 또는 SQS 큐에 동기 전달한다.
 * 클라이언트의 인증·리전·엔드포인트는 서비스가 제공하고 발행 요청마다 2초 제한을 설정한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.2.1 OutboxRelay — 이벤트 발행: SNS/SQS 메시지 속성과 호출 타임아웃</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AwsEventPublisher {
    private static final AwsRequestOverrideConfiguration REQUEST_TIMEOUT
        = AwsRequestOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(2)).build();

    // 서비스가 SNS를 사용하지 않으면 null이며 SNS를 선택한 요청에서는 실패한다.
    private final SnsClient snsClient;
    // 서비스가 SQS를 사용하지 않으면 null이며 SQS를 선택한 요청에서는 실패한다.
    private final SqsClient sqsClient;
    private final EventDestinationResolver destinationResolver;

    /**
     * DB에 저장한 메시지를 그대로 전송하고 AWS 오류를 Relay에 전달한다.
     * Outbox 상태 갱신과 커밋은 호출한 Relay의 책임이다.
     *
     * @param event 같은 식별자로 발행할 저장 이벤트
     * @throws IllegalStateException 선택한 채널의 클라이언트를 서비스가 제공하지 않았을 때
     * @throws software.amazon.awssdk.core.exception.SdkException 원격 발행 또는 타임아웃 처리에 실패할 때
     */
    public void publish(OutboxEvent event) {
        var destination = destinationResolver.resolve(event);
        switch (destination.channel()) {
            case SNS -> publishToTopic(destination.address(), event);
            case SQS -> sendToQueue(destination.address(), event);
            default -> throw new IllegalStateException("Unsupported event destination");
        }
    }

    /**
     * 구독 필터에 사용하는 속성을 JSON 본문과 같은 헤더 값으로 설정해 발행한다.
     *
     * @param topicArn 서비스가 선택한 토픽 ARN
     * @param event 원본 메시지와 헤더를 가진 발행 대상
     */
    private void publishToTopic(String topicArn, OutboxEvent event) {
        if (snsClient == null) {
            throw new IllegalStateException("An SnsClient bean is required for SNS publication");
        }
        var attributes
            = Map
                .of(
                    "eventType",
                    snsAttribute(event.metadata().eventType()),
                    "schemaVersion",
                    snsAttribute(event.metadata().schemaVersion())
                );
        snsClient
            .publish(
                PublishRequest
                    .builder()
                    .topicArn(topicArn)
                    .message(event.message())
                    .messageAttributes(attributes)
                    .overrideConfiguration(REQUEST_TIMEOUT)
                    .build()
            );
    }

    /**
     * 채점 요청처럼 큐로 직접 전달하는 이벤트에도 공통 메시지 속성을 함께 보낸다.
     *
     * @param queueUrl 서비스가 선택한 큐 URL
     * @param event 원본 메시지와 헤더를 가진 발행 대상
     */
    private void sendToQueue(String queueUrl, OutboxEvent event) {
        if (sqsClient == null) {
            throw new IllegalStateException("An SqsClient bean is required for SQS publication");
        }
        var attributes
            = Map
                .of(
                    "eventType",
                    sqsAttribute(event.metadata().eventType()),
                    "schemaVersion",
                    sqsAttribute(event.metadata().schemaVersion())
                );
        sqsClient
            .sendMessage(
                SendMessageRequest
                    .builder()
                    .queueUrl(queueUrl)
                    .messageBody(event.message())
                    .messageAttributes(attributes)
                    .overrideConfiguration(REQUEST_TIMEOUT)
                    .build()
            );
    }

    /**
     * SNS에서 사용하는 공통 헤더 속성을 문자열 타입으로 구성한다.
     *
     * @param value 본문 헤더와 동일하게 전달할 값
     * @return SNS SDK의 문자열 메시지 속성
     */
    private software.amazon.awssdk.services.sns.model.MessageAttributeValue snsAttribute(String value) {
        return software.amazon.awssdk.services.sns.model.MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue(value)
            .build();
    }

    /**
     * SQS에서 사용하는 공통 헤더 속성을 문자열 타입으로 구성한다.
     *
     * @param value 본문 헤더와 동일하게 전달할 값
     * @return SQS SDK의 문자열 메시지 속성
     */
    private software.amazon.awssdk.services.sqs.model.MessageAttributeValue sqsAttribute(String value) {
        return software.amazon.awssdk.services.sqs.model.MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue(value)
            .build();
    }
}

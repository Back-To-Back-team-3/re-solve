package com.backtoback.ai.hint.publisher;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.backtoback.ai.global.config.AiSnsProperties;
import com.backtoback.ai.hint.event.HintReadyPublishPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * ai-events SNS에 HintReady 이벤트를 발행한다 (event-contract.md §A1).
 * Outbox를 사용하지 않는 직접 발행이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HintReadyEventPublisher {

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final AiSnsProperties snsProperties;

    public void publish(HintReadyPublishPayload payload) {
        String messageBody = serialize(payload);
        snsClient
            .publish(
                PublishRequest
                    .builder()
                    .topicArn(snsProperties.topicArn())
                    .message(messageBody)
                    .messageAttributes(
                        Map
                            .of(
                                "eventType",
                                MessageAttributeValue
                                    .builder()
                                    .dataType("String")
                                    .stringValue(payload.eventType())
                                    .build(),
                                "schemaVersion",
                                MessageAttributeValue
                                    .builder()
                                    .dataType("String")
                                    .stringValue(payload.schemaVersion())
                                    .build()
                            )
                    )
                    .build()
            );
        log.info("HintReady SNS 발행 hintRequestId={} result={}", payload.hintRequestId(), payload.result());
    }

    private String serialize(HintReadyPublishPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HintReady SNS 직렬화 실패: " + e.getMessage(), e);
        }
    }
}

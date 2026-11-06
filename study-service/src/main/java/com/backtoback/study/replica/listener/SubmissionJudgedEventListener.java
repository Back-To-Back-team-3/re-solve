package com.backtoback.study.replica.listener;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.backtoback.study.replica.event.dto.SubmissionJudgedPayload;
import com.backtoback.study.replica.service.SubmissionJudgedConsumerService;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SQS {@code study-queue}에서 SubmissionJudged 이벤트를 수신하여 소비 서비스에 위임한다
 * (event-contract.md §J1, §1.4 소비 규칙).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionJudgedEventListener {

    private static final String SUPPORTED_EVENT_TYPE = "SubmissionJudged";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_EVENT_ID = "eventId";
    private static final String MDC_EVENT_TYPE = "eventType";

    private final ObjectMapper objectMapper;
    private final SubmissionJudgedConsumerService consumerService;

    @SqsListener("${study.sqs.queue-name:study-queue}")
    public void onMessage(String messageBody) {
        JsonNode payload = extractPayload(messageBody);

        String eventId = nodeString(payload, "eventId");
        String eventType = nodeString(payload, "eventType");
        if (eventId != null) {
            MDC.put(MDC_EVENT_ID, eventId);
        }
        if (eventType != null) {
            MDC.put(MDC_EVENT_TYPE, eventType);
        }
        String correlationId = nodeString(payload, "correlationId");
        if (correlationId != null) {
            MDC.put(MDC_CORRELATION_ID, correlationId);
        }

        try {
            if (!SUPPORTED_EVENT_TYPE.equals(eventType)) {
                // event-contract.md §1.4 §1: 모르는 eventType은 processed_events만 기록 후 성공
                consumerService
                    .consumeUnknown(
                        eventId,
                        eventType,
                        nodeString(payload, "occurredAt"),
                        nodeString(payload, "aggregateId"),
                        correlationId
                    );
                log.warn("처리 대상이 아닌 이벤트 무시 eventType={}", eventType);
                return;
            }

            SubmissionJudgedPayload event = objectMapper.treeToValue(payload, SubmissionJudgedPayload.class);
            consumerService.consume(event);
        } finally {
            MDC.remove(MDC_EVENT_ID);
            MDC.remove(MDC_EVENT_TYPE);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private String nodeString(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        return (fieldNode != null && !fieldNode.isNull()) ? fieldNode.asString() : null;
    }

    private JsonNode extractPayload(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // SNS Envelope(RawMessageDelivery 비활성화 환경) 언래핑
            JsonNode typeNode = root.get("Type");
            JsonNode messageNode = root.get("Message");
            if (typeNode != null && "Notification".equals(typeNode.asString()) && messageNode != null
                && messageNode.isString()) {
                try {
                    JsonNode unwrapped = objectMapper.readTree(messageNode.asString());
                    if (unwrapped != null && unwrapped.isObject()) {
                        return unwrapped;
                    }
                } catch (Exception ignored) {
                    // 언래핑 실패 시 root 그대로 사용
                }
            }
            return root;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQS 메시지 파싱 실패: " + e.getMessage(), e);
        }
    }
}

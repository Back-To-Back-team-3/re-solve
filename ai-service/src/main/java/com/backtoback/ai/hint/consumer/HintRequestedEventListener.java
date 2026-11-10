package com.backtoback.ai.hint.consumer;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.backtoback.ai.hint.dto.HintRequestedPayload;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SQS {@code ai-queue}에서 HintRequested 이벤트를 수신하여 힌트 생성 흐름에 위임한다
 * (event-contract.md §S9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HintRequestedEventListener {

    private static final String HINT_REQUESTED_EVENT_TYPE = "HintRequested";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_EVENT_ID = "eventId";
    private static final String MDC_EVENT_TYPE = "eventType";

    private final ObjectMapper objectMapper;

    @SqsListener("${ai.sqs.queue-name:ai-queue}")
    public void onMessage(String messageBody) {
        JsonNode payload = extractPayload(messageBody);

        String eventId = nodeString(payload, "eventId");
        String eventType = nodeString(payload, "eventType");
        String correlationId = nodeString(payload, "correlationId");

        if (eventId != null) {
            MDC.put(MDC_EVENT_ID, eventId);
        }
        if (eventType != null) {
            MDC.put(MDC_EVENT_TYPE, eventType);
        }
        if (correlationId != null) {
            MDC.put(MDC_CORRELATION_ID, correlationId);
        }

        try {
            if (!HINT_REQUESTED_EVENT_TYPE.equals(eventType)) {
                log.warn("처리 대상이 아닌 이벤트 무시 eventType={}", eventType);
                return;
            }

            HintRequestedPayload event = objectMapper.treeToValue(payload, HintRequestedPayload.class);
            log.info("HintRequested 수신 hintRequestId={} level={}", event.hintRequestId(), event.level());
            // TODO: 캐시 조회 후 미적중 시 LLM 힌트 생성 → HintReady 발행
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

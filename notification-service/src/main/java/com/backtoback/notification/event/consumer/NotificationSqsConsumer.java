package com.backtoback.notification.event.consumer;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.backtoback.notification.event.service.NotificationEventProcessor;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AWS SQS {@code notification-queue}로부터 도메인 이벤트를 수신하여
 * 추적 컨텍스트(MDC)를 설정하고 알림 처리기로 디스패치하는 컨슈머 (이벤트 계약서 1.4, 2.2, 5.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSqsConsumer {

    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_EVENT_ID = "eventId";
    private static final String MDC_EVENT_TYPE = "eventType";

    private final ObjectMapper objectMapper;
    private final NotificationEventProcessor eventProcessor;

    /**
     * SQS 큐 메시지를 수신하여 도메인 이벤트를 처리한다 (이벤트 계약서 1.4, 5.1).
     * 유일 제약 위반(이미 처리된 이벤트)은 정상 소비로 흡수하여 메시지를 삭제하고,
     * 그 외 역직렬화·업무 예외는 다시 던져 SQS 재시도 및 DLQ 격리 흐름을 탄다.
     *
     * @param messageBody SQS 메시지 JSON 원문
     */
    @SqsListener("${notification.sqs.queue-name:notification-queue}")
    public void consume(String messageBody) {
        JsonNode payload = extractEventPayload(messageBody);
        String eventId = extractRequiredField(payload, "eventId");
        String eventType = extractRequiredField(payload, "eventType");
        String correlationId = extractOptionalField(payload, "correlationId");

        putMdc(MDC_EVENT_ID, eventId);
        putMdc(MDC_EVENT_TYPE, eventType);
        putMdc(MDC_CORRELATION_ID, correlationId);

        try {
            log.info("도메인 이벤트 수신: eventId={}, eventType={}", eventId, eventType);
            eventProcessor.process(eventId, eventType, payload);
        } catch (DataIntegrityViolationException exception) {
            // 동시 재수신으로 인해 uk_processed_events_event_id 또는 uk_notifications_source_member_type 제약이 위반된 경우
            // 이미 처리된 이벤트이므로 예외를 삼켜 SQS 메시지를 정상 삭제한다 (이벤트 계약서 1.4).
            log.info("유일 제약 위반으로 중복 이벤트 흡수 완료: eventId={}, eventType={}", eventId, eventType);
        } finally {
            MDC.remove(MDC_EVENT_ID);
            MDC.remove(MDC_EVENT_TYPE);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private JsonNode extractEventPayload(String messageBody) {
        if (messageBody == null || messageBody.isBlank()) {
            throw new IllegalArgumentException("SQS 메시지 본문이 비어 있습니다.");
        }
        JsonNode root = objectMapper.readTree(messageBody);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("SQS 메시지 본문이 JSON 객체 형식이 아닙니다.");
        }

        // SNS RawMessageDelivery가 비활성화된 환경에서 봉투(Envelope)로 전달된 경우 내부 Message 본문을 추출한다.
        JsonNode typeNode = root.get("Type");
        JsonNode messageNode = root.get("Message");
        if (typeNode != null && "Notification".equals(typeNode.asString()) && messageNode != null
            && messageNode.isString()) {
            JsonNode unwrapped = objectMapper.readTree(messageNode.asString());
            if (unwrapped != null && unwrapped.isObject()) {
                return unwrapped;
            }
        }
        return root;
    }

    private String extractRequiredField(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("이벤트 공통 헤더 필드가 누락되었습니다: " + fieldName);
        }
        String value = node.asString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("이벤트 공통 헤더 필드가 비어 있습니다: " + fieldName);
        }
        return value;
    }

    private String extractOptionalField(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    private void putMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}

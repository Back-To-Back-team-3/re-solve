package com.backtoback.ai.hint.consumer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.ai.hint.dto.HintRequestedPayload;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SQS {@code ai-queue}에서 HintRequested 이벤트를 수신하여 힌트 생성 흐름에 위임한다
 * (event-contract.md §S9, §1.4 소비 규칙).
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
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @SqsListener("${ai.sqs.queue-name:ai-queue}")
    @Transactional
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
            // event-contract.md §1.4: processed_events INSERT를 업무 반영 첫 문장으로 실행한다.
            // hintRequestId가 UUID라 EventMetadata 유효성 검사를 통과하지 못하므로 JdbcTemplate으로 직접 삽입한다.
            LocalDateTime now = LocalDateTime.now(clock);
            try {
                jdbcTemplate
                    .update(
                        "INSERT INTO processed_events"
                            + " (event_id, event_type, processed_at, created_at) VALUES (?, ?, ?, ?)",
                        eventId,
                        eventType,
                        Timestamp.valueOf(now),
                        Timestamp.valueOf(now)
                    );
            } catch (DuplicateKeyException e) {
                log.warn("중복 수신 무시 eventId={} eventType={}", eventId, eventType);
                return;
            }

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

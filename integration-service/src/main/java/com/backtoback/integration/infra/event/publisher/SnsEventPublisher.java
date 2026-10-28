package com.backtoback.integration.infra.event.publisher;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.backtoback.integration.infra.event.domain.OutboxEvent;

import io.awspring.cloud.sns.core.SnsTemplate;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AWS SNS를 통해 Outbox 이벤트를 발행하는 구현체 (event-contract.md > 1.1, scheduler.md > 0.2.1).
 * 공통 헤더를 메시지 속성뿐만 아니라 본문(JSON) 최상위 필드로 병합하여 발행한다.
 */
@Slf4j
@Component
public class SnsEventPublisher implements EventPublisher {

    private static final DateTimeFormatter OCCURRED_AT_FORMATTER
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final ZoneOffset KST_OFFSET = ZoneOffset.of("+09:00");

    private final SnsTemplate snsTemplate;
    private final ObjectMapper objectMapper;
    private final String defaultTopicArn;

    @Autowired
    public SnsEventPublisher(
        @Autowired(required = false) SnsTemplate snsTemplate,
        ObjectMapper objectMapper,
        @Value(
            "${infra.aws.sns.default-topic-arn:arn:aws:sns:ap-northeast-2:000000000000:resolve-events}"
        ) String defaultTopicArn
    ) {
        this.snsTemplate = snsTemplate;
        this.objectMapper = objectMapper;
        this.defaultTopicArn = defaultTopicArn;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        if (snsTemplate == null) {
            throw new IllegalStateException("SnsTemplate 빈이 주입되지 않아 이벤트를 발행할 수 없습니다: eventId=" + event.getEventId());
        }

        String flatPayload = buildFlatPayload(event);

        Message<String> message
            = MessageBuilder
                .withPayload(flatPayload)
                .setHeader("eventId", event.getEventId())
                .setHeader("eventType", event.getEventType())
                .setHeader("schemaVersion", event.getSchemaVersion())
                .setHeader("correlationId", event.getCorrelationId())
                .build();

        log
            .debug(
                "SNS 이벤트 발행: eventId={}, eventType={}, topic={}",
                event.getEventId(),
                event.getEventType(),
                defaultTopicArn
            );
        snsTemplate.send(defaultTopicArn, message);
    }

    private String buildFlatPayload(OutboxEvent event) throws Exception {
        ObjectNode rootNode;
        String rawPayload = event.getPayload();
        if (rawPayload != null && !rawPayload.isBlank()) {
            JsonNode parsed = objectMapper.readTree(rawPayload);
            if (parsed instanceof ObjectNode objectNode) {
                rootNode = objectNode;
            } else {
                rootNode = objectMapper.createObjectNode();
                rootNode.set("payload", parsed);
            }
        } else {
            rootNode = objectMapper.createObjectNode();
        }

        rootNode.put("eventId", event.getEventId());
        rootNode.put("eventType", event.getEventType());
        rootNode.put("schemaVersion", event.getSchemaVersion());
        rootNode.put("aggregateId", event.getAggregateId());
        rootNode.put("correlationId", event.getCorrelationId());
        if (event.getOccurredAt() != null) {
            rootNode.put("occurredAt", event.getOccurredAt().atOffset(KST_OFFSET).format(OCCURRED_AT_FORMATTER));
        }

        return objectMapper.writeValueAsString(rootNode);
    }
}

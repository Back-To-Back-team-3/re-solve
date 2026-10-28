package com.backtoback.integration.infra.event.publisher;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.backtoback.integration.infra.event.domain.OutboxEvent;

import io.awspring.cloud.sns.core.SnsTemplate;
import lombok.extern.slf4j.Slf4j;

/**
 * AWS SNS를 통해 Outbox 이벤트를 발행하는 구현체 (scheduler.md > 0.2.1).
 */
@Slf4j
@Component
public class SnsEventPublisher implements EventPublisher {

    private final SnsTemplate snsTemplate;
    private final String defaultTopicArn;

    @Autowired
    public SnsEventPublisher(
        @Autowired(required = false) SnsTemplate snsTemplate,
        @Value(
            "${infra.aws.sns.default-topic-arn:arn:aws:sns:ap-northeast-2:000000000000:resolve-events}"
        ) String defaultTopicArn
    ) {
        this.snsTemplate = snsTemplate;
        this.defaultTopicArn = defaultTopicArn;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        if (snsTemplate == null) {
            throw new IllegalStateException("SnsTemplate 빈이 주입되지 않아 이벤트를 발행할 수 없습니다: eventId=" + event.getEventId());
        }

        Message<String> message
            = MessageBuilder
                .withPayload(event.getPayload())
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
}

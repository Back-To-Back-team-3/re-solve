package com.backtoback.integration.infra.event.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import com.backtoback.integration.infra.event.domain.OutboxEvent;

import io.awspring.cloud.sns.core.SnsTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SnsEventPublisherTest {

    @Mock
    private SnsTemplate snsTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("SnsTemplate이 null이면 이벤트를 조용히 완료하지 않고 IllegalStateException을 던진다")
    void publishThrowsWhenSnsTemplateIsNull() {
        SnsEventPublisher publisher = new SnsEventPublisher(null, objectMapper, "arn:aws:sns:test");
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-1234")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("100")
                .correlationId("corr-1")
                .payload("{}")
                .occurredAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> publisher.publish(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SnsTemplate 빈이 주입되지 않아");
    }

    @Test
    @DisplayName("정상 발행 시 eventId, eventType, schemaVersion, correlationId 헤더와 본문 공통 필드가 병합된다")
    void publishIncludesHeadersAndMergesCommonFieldsIntoPayload() throws Exception {
        SnsEventPublisher publisher = new SnsEventPublisher(snsTemplate, objectMapper, "arn:aws:sns:test-topic");
        LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 20, 21, 29, 59, 123456000);
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-1234")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("100")
                .correlationId("corr-1")
                .payload("{\"key\":\"value\"}")
                .occurredAt(occurredAt)
                .build();

        publisher.publish(event);

        @SuppressWarnings("unchecked") ArgumentCaptor<Message<String>> messageCaptor
            = ArgumentCaptor.forClass(Message.class);
        verify(snsTemplate).send(eq("arn:aws:sns:test-topic"), messageCaptor.capture());

        Message<String> captured = messageCaptor.getValue();
        // 헤더 검증
        assertThat(captured.getHeaders().get("eventId")).isEqualTo("evt-1234");
        assertThat(captured.getHeaders().get("eventType")).isEqualTo("SubmissionJudged");
        assertThat(captured.getHeaders().get("schemaVersion")).isEqualTo("v1");
        assertThat(captured.getHeaders().get("correlationId")).isEqualTo("corr-1");

        // 본문(JSON) 공통 필드 병합 검증
        JsonNode payloadNode = objectMapper.readTree(captured.getPayload());
        assertThat(payloadNode.get("key").asText()).isEqualTo("value");
        assertThat(payloadNode.get("eventId").asText()).isEqualTo("evt-1234");
        assertThat(payloadNode.get("eventType").asText()).isEqualTo("SubmissionJudged");
        assertThat(payloadNode.get("schemaVersion").asText()).isEqualTo("v1");
        assertThat(payloadNode.get("aggregateId").asText()).isEqualTo("100");
        assertThat(payloadNode.get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(payloadNode.get("occurredAt").asText()).isEqualTo("2026-10-20T21:29:59.123456+09:00");
    }
}

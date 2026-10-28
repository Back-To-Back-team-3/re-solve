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

@ExtendWith(MockitoExtension.class)
class SnsEventPublisherTest {

    @Mock
    private SnsTemplate snsTemplate;

    @Test
    @DisplayName("SnsTemplate이 null이면 이벤트를 조용히 완료하지 않고 IllegalStateException을 던진다")
    void publishThrowsWhenSnsTemplateIsNull() {
        SnsEventPublisher publisher = new SnsEventPublisher(null, "arn:aws:sns:test");
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
    @DisplayName("정상 발행 시 eventId, eventType, schemaVersion, correlationId 헤더가 메시지에 포함된다")
    void publishIncludesAllHeaders() throws Exception {
        SnsEventPublisher publisher = new SnsEventPublisher(snsTemplate, "arn:aws:sns:test-topic");
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
                .occurredAt(LocalDateTime.now())
                .build();

        publisher.publish(event);

        @SuppressWarnings("unchecked") ArgumentCaptor<Message<String>> messageCaptor
            = ArgumentCaptor.forClass(Message.class);
        verify(snsTemplate).send(eq("arn:aws:sns:test-topic"), messageCaptor.capture());

        Message<String> captured = messageCaptor.getValue();
        assertThat(captured.getPayload()).isEqualTo("{\"key\":\"value\"}");
        assertThat(captured.getHeaders().get("eventId")).isEqualTo("evt-1234");
        assertThat(captured.getHeaders().get("eventType")).isEqualTo("SubmissionJudged");
        assertThat(captured.getHeaders().get("schemaVersion")).isEqualTo("v1");
        assertThat(captured.getHeaders().get("correlationId")).isEqualTo("corr-1");
    }
}

package com.backtoback.integration.infra.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    @Test
    @DisplayName("필수 정보가 주어지면 PENDING 상태와 retryCount 0으로 엔티티가 정상 생성된다")
    void createOutboxEventSuccess() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-1234")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("91023")
                .correlationId("corr-5678")
                .payload("{\"submissionId\":91023,\"verdict\":\"AC\"}")
                .occurredAt(now)
                .build();

        assertThat(event.getEventId()).isEqualTo("evt-1234");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("필수 필드가 누락되면 IllegalArgumentException이 발생한다")
    void createOutboxEventValidation() {
        assertThatThrownBy(() -> OutboxEvent.builder().build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markPublished 호출 시 PUBLISHED 상태로 전이되고 발행 시각이 기록된다")
    void markPublishedSuccess() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-1234")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("91023")
                .correlationId("corr-5678")
                .payload("{}")
                .occurredAt(now)
                .build();

        LocalDateTime publishedAt = now.plusSeconds(1);
        event.markPublished(publishedAt);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    @DisplayName("실패 기록 시 retryCount가 증가하고 최대 재시도(5회)를 초과하면 FAILED 상태로 전이된다")
    void recordFailureTransitionsToFailedAfterMaxRetries() {
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-1234")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("91023")
                .correlationId("corr-5678")
                .payload("{}")
                .occurredAt(LocalDateTime.now())
                .build();

        // 1~5회 실패: 여전히 PENDING
        for (int i = 1; i <= 5; i++) {
            event.recordFailure(5);
            assertThat(event.getRetryCount()).isEqualTo(i);
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        }

        // 6회째 실패: FAILED로 전이
        event.recordFailure(5);
        assertThat(event.getRetryCount()).isEqualTo(6);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }
}

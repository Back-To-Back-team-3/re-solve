package com.backtoback.integration.infra.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcessedEventTest {

    @Test
    @DisplayName("필수 정보가 주어지면 ProcessedEvent 엔티티가 정상 생성된다")
    void createProcessedEventSuccess() {
        LocalDateTime now = LocalDateTime.now();
        ProcessedEvent event
            = ProcessedEvent
                .builder()
                .eventId("evt-1234")
                .eventType("SubmissionJudged")
                .processedAt(now)
                .createdAt(now)
                .build();

        assertThat(event.getEventId()).isEqualTo("evt-1234");
        assertThat(event.getEventType()).isEqualTo("SubmissionJudged");
        assertThat(event.getProcessedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("필수 필드가 누락되면 IllegalArgumentException이 발생한다")
    void createProcessedEventValidation() {
        assertThatThrownBy(() -> ProcessedEvent.builder().build()).isInstanceOf(IllegalArgumentException.class);
    }
}

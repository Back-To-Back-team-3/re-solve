package com.backtoback.integration.infra.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.integration.infra.event.domain.OutboxEvent;
import com.backtoback.integration.infra.event.domain.OutboxEventStatus;
import com.backtoback.integration.infra.event.publisher.EventPublisher;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private EventPublisher eventPublisher;

    private Clock fixedClock;
    private OutboxRelayService outboxRelayService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneId.of("Asia/Seoul"));
        outboxRelayService = new OutboxRelayService(outboxEventRepository, eventPublisher, fixedClock);
    }

    @Test
    @DisplayName("PENDING 이벤트가 없으면 0을 반환한다")
    void relayWhenEmpty() {
        given(outboxEventRepository.findPendingEventsForUpdateSkipLocked(100)).willReturn(Collections.emptyList());

        int count = outboxRelayService.relayPendingEvents();

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("이벤트 발행 성공 시 PUBLISHED 상태로 전이되고 발행 시각이 기록된다")
    void relaySuccess() throws Exception {
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-1")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("100")
                .correlationId("c-1")
                .payload("{}")
                .occurredAt(LocalDateTime.now())
                .build();

        given(outboxEventRepository.findPendingEventsForUpdateSkipLocked(100)).willReturn(List.of(event));

        int count = outboxRelayService.relayPendingEvents();

        assertThat(count).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(eventPublisher).publish(event);
    }

    @Test
    @DisplayName("이벤트 발행 실패 시 retryCount가 증가하고 다음 이벤트는 정상 처리된다")
    void relayFailureContinuesNextEvent() throws Exception {
        OutboxEvent failEvent
            = OutboxEvent
                .builder()
                .eventId("evt-fail")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("100")
                .correlationId("c-fail")
                .payload("{}")
                .occurredAt(LocalDateTime.now())
                .build();

        OutboxEvent successEvent
            = OutboxEvent
                .builder()
                .eventId("evt-success")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("101")
                .correlationId("c-success")
                .payload("{}")
                .occurredAt(LocalDateTime.now())
                .build();

        given(outboxEventRepository.findPendingEventsForUpdateSkipLocked(100))
            .willReturn(List.of(failEvent, successEvent));

        doThrow(new RuntimeException("SNS Connection Timeout")).when(eventPublisher).publish(failEvent);

        int count = outboxRelayService.relayPendingEvents();

        assertThat(count).isEqualTo(2);
        // 실패 이벤트: retryCount = 1, 여전히 PENDING
        assertThat(failEvent.getRetryCount()).isEqualTo(1);
        assertThat(failEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // 성공 이벤트: PUBLISHED
        assertThat(successEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("재시도 상한(5회) 초과 시 FAILED 상태로 전이된다")
    void relayFailureTransitionsToFailedAfterMaxRetries() throws Exception {
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("evt-max-fail")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("100")
                .correlationId("c-fail")
                .payload("{}")
                .occurredAt(LocalDateTime.now())
                .build();

        // 이미 5회 실패한 상태
        for (int i = 0; i < 5; i++) {
            event.recordFailure(5);
        }

        given(outboxEventRepository.findPendingEventsForUpdateSkipLocked(100)).willReturn(List.of(event));

        doThrow(new RuntimeException("SNS Error")).when(eventPublisher).publish(any());

        outboxRelayService.relayPendingEvents();

        assertThat(event.getRetryCount()).isEqualTo(6);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }
}

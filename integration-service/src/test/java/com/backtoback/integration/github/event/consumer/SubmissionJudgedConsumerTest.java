package com.backtoback.integration.github.event.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.backtoback.integration.github.service.GithubSyncJobService;
import com.backtoback.integration.infra.event.domain.ProcessedEvent;
import com.backtoback.integration.infra.event.repository.ProcessedEventRepository;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SubmissionJudgedConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private GithubSyncJobService githubSyncJobService;

    private Clock fixedClock;
    private ObjectMapper objectMapper;
    private SubmissionJudgedConsumer consumer;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Asia/Seoul"));
        objectMapper = new ObjectMapper();
        consumer
            = new SubmissionJudgedConsumer(objectMapper, processedEventRepository, githubSyncJobService, fixedClock);
    }

    private String createEventJson(String eventId, String eventType, String verdict) {
        return """
            {
              "eventId": "%s",
              "eventType": "%s",
              "schemaVersion": "v1",
              "occurredAt": "2026-10-29T10:00:00+09:00",
              "aggregateId": "100",
              "correlationId": "c-123",
              "submissionId": "100",
              "userId": "1",
              "problemId": "200",
              "verdict": "%s",
              "problemTitle": "두 수의 합",
              "problemDifficulty": 1,
              "language": "python3"
            }
            """.formatted(eventId, eventType, verdict);
    }

    @Test
    @DisplayName("이미 처리된 eventId는 중복 소비를 무시하고 작업을 등록하지 않는다")
    void consumeDuplicateEventIgnored() {
        String json = createEventJson("evt-1", "SubmissionJudged", "AC");
        given(processedEventRepository.existsByEventId("evt-1")).willReturn(true);

        consumer.consume(json);

        verify(processedEventRepository, never()).saveAndFlush(any());
        verify(githubSyncJobService, never()).registerSyncJob(any(), any(), any(), any(), eq(1));
    }

    @Test
    @DisplayName("AC 판정의 신규 이벤트는 ProcessedEvent 기록 후 Sync Job을 등록한다")
    void consumeAcceptedEventRegistersSyncJob() {
        String json = createEventJson("evt-2", "SubmissionJudged", "AC");
        given(processedEventRepository.existsByEventId("evt-2")).willReturn(false);

        consumer.consume(json);

        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        verify(githubSyncJobService).registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);
    }

    @Test
    @DisplayName("WA 등 오답 판정의 이벤트는 ProcessedEvent만 기록하고 Sync Job을 생성하지 않는다")
    void consumeWrongAnswerEventSkipsSyncJob() {
        String json = createEventJson("evt-3", "SubmissionJudged", "WA");
        given(processedEventRepository.existsByEventId("evt-3")).willReturn(false);

        consumer.consume(json);

        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        verify(githubSyncJobService, never()).registerSyncJob(any(), any(), any(), any(), eq(1));
    }

    @Test
    @DisplayName("SNS Envelope 봉투 형식의 메시지도 내부 JSON을 정상 언래핑하여 처리한다")
    void consumeSnsEnvelopedMessage() {
        String innerJson = createEventJson("evt-4", "SubmissionJudged", "AC");
        String enveloped = """
            {
              "Type": "Notification",
              "MessageId": "msg-1",
              "Message": %s
            }
            """.formatted(objectMapper.writeValueAsString(innerJson));

        given(processedEventRepository.existsByEventId("evt-4")).willReturn(false);

        consumer.consume(enveloped);

        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
        verify(githubSyncJobService).registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);
    }

    @Test
    @DisplayName("동시 수신으로 유니크 제약 위반 시 예외를 삼키고 정상 완료한다")
    void consumeDataIntegrityViolationHandledGracefully() {
        String json = createEventJson("evt-5", "SubmissionJudged", "AC");
        given(processedEventRepository.existsByEventId("evt-5")).willReturn(false);
        given(processedEventRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("unique"));

        consumer.consume(json);

        verify(githubSyncJobService, never()).registerSyncJob(any(), any(), any(), any(), eq(1));
    }

    @Test
    @DisplayName("필수 헤더가 누락된 메시지는 IllegalArgumentException을 던진다")
    void consumeInvalidPayloadThrowsException() {
        String invalidJson = "{\"dummy\": \"value\"}";

        assertThatThrownBy(() -> consumer.consume(invalidJson)).isInstanceOf(RuntimeException.class);
    }
}

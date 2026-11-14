package com.backtoback.study.hint.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.event.dto.HintReadyConsumedPayload;
import com.backtoback.study.hint.repository.HintRequestRepository;

/**
 * HintReady 소비 케이스별 단위 테스트 (event-contract.md §S8, §1.4 멱등 처리).
 */
@ExtendWith(MockitoExtension.class)
class HintReadyConsumerServiceTest {

    private static final String HINT_REQUEST_ID = "1001";
    private static final String MEMBER_ID = "10";
    private static final String PROBLEM_ID = "100";
    private static final String PROBLEM_REVISION_ID = "200";
    private static final int LEVEL = 1;
    private static final String COMPLETED_AT = "2026-11-11T10:00:00+09:00";

    @Spy
    private Clock clock = Clock.systemDefaultZone();
    @Mock
    private JdbcProcessedEventStore processedEventStore;
    @Mock
    private HintRequestRepository hintRequestRepository;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    @InjectMocks
    private HintReadyConsumerService service;

    @Test
    @DisplayName("result=READY 이벤트를 수신하면 hint_requests를 READY로 갱신하고 HintReady Outbox를 발행한다")
    void consume_resultReady_updatesStatusAndPublishesOutbox() {
        // given
        HintReadyConsumedPayload payload = payload("READY", true);
        given(
            hintRequestRepository
                .completeIfGenerating(
                    eq(Long.parseLong(HINT_REQUEST_ID)),
                    eq(HintRequestStatus.READY),
                    any(),
                    any(),
                    eq(true),
                    eq(false),
                    eq(HintRequestStatus.GENERATING)
                )
        ).willReturn(1);

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository)
            .completeIfGenerating(
                eq(Long.parseLong(HINT_REQUEST_ID)),
                eq(HintRequestStatus.READY),
                any(),
                any(LocalDateTime.class),
                eq(true),
                eq(false),
                eq(HintRequestStatus.GENERATING)
            );
        verify(outboxEventWriter).append(anyString(), eq("HintReady"), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("result=FALLBACK 이벤트를 수신하면 hint_requests를 FALLBACK·is_fallback=true로 갱신하고 Outbox를 발행한다")
    void consume_resultFallback_updatesFallbackStatusAndPublishesOutbox() {
        // given
        HintReadyConsumedPayload payload = payload("FALLBACK", false);
        given(
            hintRequestRepository
                .completeIfGenerating(
                    eq(Long.parseLong(HINT_REQUEST_ID)),
                    eq(HintRequestStatus.FALLBACK),
                    any(),
                    any(),
                    eq(false),
                    eq(true),
                    eq(HintRequestStatus.GENERATING)
                )
        ).willReturn(1);

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository)
            .completeIfGenerating(
                eq(Long.parseLong(HINT_REQUEST_ID)),
                eq(HintRequestStatus.FALLBACK),
                any(),
                any(),
                eq(false),
                eq(true),
                eq(HintRequestStatus.GENERATING)
            );
        verify(outboxEventWriter).append(anyString(), eq("HintReady"), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("result=BLOCKED 이벤트를 수신하면 hint_requests를 BLOCKED로 갱신하고 Outbox를 발행한다")
    void consume_resultBlocked_updatesBlockedStatusAndPublishesOutbox() {
        // given
        HintReadyConsumedPayload payload = payload("BLOCKED", false);
        given(
            hintRequestRepository
                .completeIfGenerating(
                    eq(Long.parseLong(HINT_REQUEST_ID)),
                    eq(HintRequestStatus.BLOCKED),
                    any(),
                    any(),
                    eq(false),
                    eq(false),
                    eq(HintRequestStatus.GENERATING)
                )
        ).willReturn(1);

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository)
            .completeIfGenerating(
                eq(Long.parseLong(HINT_REQUEST_ID)),
                eq(HintRequestStatus.BLOCKED),
                any(),
                any(),
                eq(false),
                eq(false),
                eq(HintRequestStatus.GENERATING)
            );
        verify(outboxEventWriter).append(anyString(), eq("HintReady"), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("이미 처리된 eventId를 수신하면 상태 갱신과 Outbox 발행 없이 무시한다")
    void consume_duplicateEventId_skipsUpdateAndOutbox() {
        // given
        HintReadyConsumedPayload payload = payload("READY", true);
        willThrow(new DuplicateKeyException("중복"))
            .given(processedEventStore)
            .insert(any(EventMetadata.class), any(LocalDateTime.class));

        // when
        service.consume(payload);

        // then
        verify(hintRequestRepository, never())
            .completeIfGenerating(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("hint_requests가 GENERATING 상태가 아니면 0 rows로 반환되어 Outbox 발행 없이 종료한다")
    void consume_statusNotGenerating_zeroRowsSkipsOutbox() {
        // given
        HintReadyConsumedPayload payload = payload("READY", false);
        given(hintRequestRepository.completeIfGenerating(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
            .willReturn(0);

        // when
        service.consume(payload);

        // then
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 hintRequestId를 수신하면 0 rows로 반환되어 Outbox 발행 없이 종료한다")
    void consume_nonExistentHintRequestId_zeroRowsSkipsOutbox() {
        // given
        HintReadyConsumedPayload payload
            = new HintReadyConsumedPayload(
                UUID.randomUUID().toString(),
                "2026-11-11T01:00:00.000000+09:00",
                "99999",
                "HintReady",
                "99999",
                MEMBER_ID,
                PROBLEM_ID,
                PROBLEM_REVISION_ID,
                LEVEL,
                "READY",
                false,
                COMPLETED_AT
            );
        given(hintRequestRepository.completeIfGenerating(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
            .willReturn(0);

        // when
        service.consume(payload);

        // then
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    private HintReadyConsumedPayload payload(String result, boolean isCached) {
        return new HintReadyConsumedPayload(
            UUID.randomUUID().toString(),
            "2026-11-11T01:00:00.000000+09:00",
            HINT_REQUEST_ID,
            "HintReady",
            HINT_REQUEST_ID,
            MEMBER_ID,
            PROBLEM_ID,
            PROBLEM_REVISION_ID,
            LEVEL,
            result,
            isCached,
            COMPLETED_AT
        );
    }
}

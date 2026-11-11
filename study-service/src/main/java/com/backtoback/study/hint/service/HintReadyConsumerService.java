package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.event.HintReadyPayload;
import com.backtoback.study.hint.event.dto.HintReadyConsumedPayload;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HintReady 이벤트를 소비하여 hint_requests 상태를 갱신하고 Outbox를 재발행한다
 * (event-contract.md §S8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HintReadyConsumerService {

    private static final String HINT_REQUEST_AGGREGATE_TYPE = "HINT_REQUEST";
    private static final String HINT_READY_EVENT_TYPE = "HintReady";
    private static final String HINT_READY_VERSION = "v1";

    private final Clock clock;
    private final JdbcProcessedEventStore processedEventStore;
    private final HintRequestRepository hintRequestRepository;
    private final OutboxEventWriter outboxEventWriter;

    /**
     * HintReady 이벤트를 소비한다.
     * processed_events INSERT → 조건부 UPDATE → Outbox 재발행 순으로 한 트랜잭션에서 처리한다.
     * 0 rows affected이면 이미 처리된 행이므로 Outbox 발행 없이 종료한다.
     */
    @Transactional
    public void consume(HintReadyConsumedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("HintReady 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

        Long hintRequestId = Long.parseLong(payload.hintRequestId());
        HintRequestStatus newStatus = HintRequestStatus.valueOf(payload.result());
        boolean isFallback = newStatus == HintRequestStatus.FALLBACK;
        LocalDateTime completedAt
            = payload.completedAt() != null ? OffsetDateTime
                .parse(payload.completedAt())
                .toInstant()
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDateTime()
                .truncatedTo(ChronoUnit.MICROS) : null;

        int updated
            = hintRequestRepository
                .completeIfGenerating(
                    hintRequestId,
                    newStatus,
                    null, // event-contract.md §8.2 미결 항목 6 확정 전 NULL
                    completedAt,
                    payload.isCached(),
                    isFallback,
                    HintRequestStatus.GENERATING
                );

        if (updated == 0) {
            log.warn("HintReady 상태 갱신 대상 없음 (이미 처리됨) hintRequestId={} result={}", hintRequestId, payload.result());
            return;
        }

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        OffsetDateTime completedAtOffset
            = payload.completedAt() != null ? OffsetDateTime.parse(payload.completedAt()) : null;

        outboxEventWriter
            .append(
                HINT_REQUEST_AGGREGATE_TYPE,
                HINT_READY_EVENT_TYPE,
                HINT_READY_VERSION,
                String.valueOf(hintRequestId),
                correlationId,
                new HintReadyPayload(
                    payload.hintRequestId(),
                    payload.memberId(),
                    payload.problemId(),
                    payload.problemRevisionId(),
                    payload.level(),
                    payload.result(),
                    payload.isCached(),
                    completedAtOffset
                )
            );
    }
}

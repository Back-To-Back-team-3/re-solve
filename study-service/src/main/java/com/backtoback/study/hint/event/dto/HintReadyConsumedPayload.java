package com.backtoback.study.hint.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * S8 HintReady v1 소비 페이로드 (event-contract.md §S8).
 * study-service 소비 필드만 포함한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HintReadyConsumedPayload(
    String eventId,
    String occurredAt,
    String aggregateId,
    String eventType,
    String hintRequestId,
    String memberId,
    String problemId,
    String problemRevisionId,
    int level,
    String result,
    boolean isCached,
    String completedAt
) {

    public EventMetadata toMetadata() {
        return new EventMetadata(
            UUID.fromString(eventId),
            eventType,
            "v1",
            OffsetDateTime.parse(occurredAt),
            aggregateId,
            eventId
        );
    }
}

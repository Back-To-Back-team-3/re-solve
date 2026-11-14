package com.backtoback.study.hint.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 시험 종료·취소 이벤트 페이로드 (event-contract.md §C9 ExamClosed·§C10 ExamCanceled v1).
 * study-service 소비 필드만 포함한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExamTerminatedPayload(
    String eventId,
    String occurredAt,
    String aggregateId,
    String eventType,
    String examId
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

package com.backtoback.study.hint.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 시험 시작 이벤트 페이로드 (event-contract.md §C8 ExamStarted v1).
 * study-service 소비 필드만 포함한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExamStartedPayload(
    String eventId,
    String occurredAt,
    String aggregateId,
    String examId,
    String studyId,
    String examRevision,
    String startsAt,
    String endsAt
) {

    public EventMetadata toMetadata() {
        return new EventMetadata(
            UUID.fromString(eventId),
            "ExamStarted",
            "v1",
            OffsetDateTime.parse(occurredAt),
            aggregateId,
            eventId
        );
    }
}

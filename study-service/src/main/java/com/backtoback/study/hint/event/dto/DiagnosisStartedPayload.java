package com.backtoback.study.hint.event.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 진단 시작 이벤트 페이로드 (event-contract.md §M7 DiagnosisStarted v1).
 * study-service 소비 필드만 포함한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiagnosisStartedPayload(
    String eventId,
    String occurredAt,
    String aggregateId,
    String diagnosisAttemptId,
    String memberId,
    List<String> problemIds,
    String startsAt,
    String endsAt
) {

    public EventMetadata toMetadata() {
        return new EventMetadata(
            UUID.fromString(eventId),
            "DiagnosisStarted",
            "v1",
            OffsetDateTime.parse(occurredAt),
            aggregateId,
            eventId
        );
    }
}

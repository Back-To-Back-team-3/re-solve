package com.backtoback.study.hint.event.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 참가 등록 이벤트 페이로드 (event-contract.md §C3 ExamParticipantRegistered·§C4 ContestParticipantRegistered v1).
 * study-service 소비 필드만 포함한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParticipantRegisteredPayload(
    String eventId,
    String occurredAt,
    String aggregateId,
    String eventType,
    String context,
    String contextId,
    String memberId,
    String contextRevision,
    List<String> problemIds,
    String startsAt,
    String endsAt
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

package com.backtoback.study.hint.event.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 시험 설정 변경 이벤트 페이로드 (event-contract.md §C7 ExamUpdated v1).
 * study-service 소비 필드만 포함한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExamUpdatedPayload(
    String eventId,
    String occurredAt,
    String aggregateId,
    String examId,
    String examRevision,
    List<ExamProblem> problems,
    String startsAt,
    String endsAt,
    List<String> targetMemberIds
) {

    public EventMetadata toMetadata() {
        return new EventMetadata(
            UUID.fromString(eventId),
            "ExamUpdated",
            "v1",
            OffsetDateTime.parse(occurredAt),
            aggregateId,
            eventId
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExamProblem(String problemId) {
    }
}

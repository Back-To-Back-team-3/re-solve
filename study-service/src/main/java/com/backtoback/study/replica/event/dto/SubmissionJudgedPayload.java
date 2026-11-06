package com.backtoback.study.replica.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * judge-service 채점 완료 도메인 이벤트 페이로드 (event-contract.md > §J1 SubmissionJudged v1).
 * study-service 소비 필드만 포함하며, 나머지 필드는 ignoreUnknown으로 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionJudgedPayload(
    String eventId,
    String eventType,
    String schemaVersion,
    String occurredAt,
    String aggregateId,
    String correlationId,
    String submissionId,
    String userId,
    String problemId,
    String verdict,
    Integer judgeAttempt,
    String submittedAt,
    String judgedAt
) {

    public boolean isAccepted() {
        return "AC".equalsIgnoreCase(verdict);
    }

    /**
     * 소비 기록 저장용 EventMetadata를 생성한다.
     * correlationId가 없는 이벤트는 "anonymous"로 대체한다(EventMetadata 필수 검증 통과 목적).
     */
    public EventMetadata toMetadata() {
        String timeSource = judgedAt != null ? judgedAt : occurredAt;
        OffsetDateTime odt = OffsetDateTime.parse(timeSource);
        String corrId = (correlationId != null && !correlationId.isBlank()) ? correlationId : "anonymous";
        return new EventMetadata(UUID.fromString(eventId), "SubmissionJudged", "v1", odt, submissionId, corrId);
    }
}

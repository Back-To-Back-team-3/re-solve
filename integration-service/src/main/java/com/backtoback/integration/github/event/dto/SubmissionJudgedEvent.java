package com.backtoback.integration.github.event.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * judge-service 채점 완료 도메인 이벤트 DTO (event-contract.md > 4.3 J1 SubmissionJudged v1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionJudgedEvent(
    String eventId,
    String eventType,
    String schemaVersion,
    String occurredAt,
    String aggregateId,
    String correlationId,
    String submissionId,
    String userId,
    String problemId,
    String problemRevisionId,
    String verdict,
    Integer judgeAttempt,
    String submittedAt,
    String judgedAt,
    Integer passedCount,
    Integer totalCount,
    Integer maxTimeMs,
    Integer maxMemoryKb,
    String language,
    Integer problemDifficulty,
    List<String> problemTags,
    List<String> problemTagIds,
    String problemTitle
) {
    public boolean isAccepted() {
        return "AC".equalsIgnoreCase(verdict);
    }
}

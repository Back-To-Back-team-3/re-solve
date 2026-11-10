package com.backtoback.ai.hint.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * study-service 힌트 요청 도메인 이벤트 페이로드 (event-contract.md > §S9 HintRequested v1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HintRequestedPayload(
    String eventId,
    String eventType,
    String schemaVersion,
    String occurredAt,
    String aggregateId,
    String correlationId,
    String hintRequestId,
    String memberId,
    String problemId,
    String problemRevisionId,
    Integer level,
    String problemTitle,
    String problemBody,
    List<String> problemTags
) {
}

package com.backtoback.ai.hint.event;

/**
 * ai-events SNS 발행 페이로드 (event-contract.md §A1 HintReady v1).
 * 공통 헤더와 업무 필드를 평평한 JSON 하나로 직렬화한다.
 */
public record HintReadyPublishPayload(
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
    int level,
    String result,
    boolean isCached,
    String completedAt
) {

}

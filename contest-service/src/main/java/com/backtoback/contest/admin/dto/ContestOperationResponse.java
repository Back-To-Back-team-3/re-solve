package com.backtoback.contest.admin.dto;

import java.time.OffsetDateTime;

/** 관리자 작업의 현재 상태다. */
public record ContestOperationResponse(
    String operationId,
    String operationType,
    String context,
    String contextId,
    String receiptId,
    String state,
    Integer expectedJudgeAttempt,
    Integer targetJudgeAttempt,
    String lastErrorCode,
    OffsetDateTime updatedAt
) {
}

package com.backtoback.contest.admin.dto;

/** 수동 종결된 시험 접수와 결과 버전이다. */
public record ExamResolutionResponse(
    String operationId,
    String context,
    String receiptId,
    String status,
    boolean resolved,
    String resultRevision
) {
}

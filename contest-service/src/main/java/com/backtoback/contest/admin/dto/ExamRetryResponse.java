package com.backtoback.contest.admin.dto;

/** 원격 재처리 작업과 접수의 현재 회차다. */
public record ExamRetryResponse(
    String operationId,
    String state,
    String examSubmissionId,
    String submissionId,
    int judgeAttempt,
    Integer targetJudgeAttempt
) {
}

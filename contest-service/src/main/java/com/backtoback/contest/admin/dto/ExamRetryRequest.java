package com.backtoback.contest.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 실패 접수 재처리 작업과 대상 회차다. */
public record ExamRetryRequest(
    @NotNull
    @Size(
        min = 1,
        max = 100
    ) String operationId,
    @NotNull @Min(1) Integer expectedJudgeAttempt,
    @NotNull
    @Size(
        min = 1,
        max = 500
    ) String reason
) {
}

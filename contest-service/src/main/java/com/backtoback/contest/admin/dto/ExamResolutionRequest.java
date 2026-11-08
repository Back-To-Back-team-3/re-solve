package com.backtoback.contest.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 실패 시험 접수의 수동 종결 작업과 기대 회차다. */
public record ExamResolutionRequest(
    @NotNull
    @Size(
        min = 1,
        max = 100
    ) String operationId,
    @NotBlank String action,
    @NotNull @Min(0) Integer expectedJudgeAttempt,
    @NotNull
    @Size(
        min = 1,
        max = 500
    ) String reason
) {
}

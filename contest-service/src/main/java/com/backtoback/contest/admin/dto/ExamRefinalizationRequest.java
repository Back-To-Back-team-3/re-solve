package com.backtoback.contest.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 재확정할 이전 결과 버전과 관리자 사유다. */
public record ExamRefinalizationRequest(
    @NotNull
    @Size(
        min = 1,
        max = 100
    ) String operationId,
    @NotBlank @Pattern(regexp = "0|[1-9][0-9]*") String expectedResultRevision,
    @NotNull
    @Size(
        min = 1,
        max = 500
    ) String reason
) {
}

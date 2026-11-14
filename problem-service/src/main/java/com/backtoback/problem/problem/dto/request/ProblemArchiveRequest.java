package com.backtoback.problem.problem.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 보관 기대 버전과 감사 사유다. */
public record ProblemArchiveRequest(
    @NotNull(message = "문제 버전은 필수입니다.")
    @Min(
        value = 1,
        message = "문제 버전은 1 이상이어야 합니다."
    ) Integer rowVersion,
    @NotBlank(message = "보관 사유는 필수입니다.")
    @Size(
        max = 500,
        message = "보관 사유는 500자 이하여야 합니다."
    ) String reason
) {
}

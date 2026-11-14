package com.backtoback.problem.problem.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 공개할 문제의 기대 버전이다. */
public record ProblemPublicationRequest(
    @NotNull(message = "문제 버전은 필수입니다.")
    @Min(
        value = 1,
        message = "문제 버전은 1 이상이어야 합니다."
    ) Integer rowVersion
) {
}

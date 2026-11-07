package com.backtoback.problem.problem.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 보관 기대 버전과 감사 사유다. */
public record ProblemArchiveRequest(@NotNull @Min(1) Integer rowVersion, @NotBlank @Size(max = 500) String reason) {
}

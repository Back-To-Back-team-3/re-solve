package com.backtoback.study.hint.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record HintRequestBody(@NotBlank String problemId, @Min(1) @Max(3) int level) {
}

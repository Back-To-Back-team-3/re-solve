package com.backtoback.contest.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 시작 전 시험 취소에 필요한 조회 버전과 사유다. */
public record ExamCancelRequest(
    @NotBlank @Pattern(regexp = "0|[1-9][0-9]*") String version,
    @NotNull
    @Size(
        min = 1,
        max = 500
    ) String reason
) {
}

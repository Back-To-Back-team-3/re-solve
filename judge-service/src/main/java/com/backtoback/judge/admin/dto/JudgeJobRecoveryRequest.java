package com.backtoback.judge.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 복구 사유. */
public record JudgeJobRecoveryRequest(
    @NotBlank(message = "복구 사유가 필요합니다.")
    @Size(
        max = 500,
        message = "복구 사유는 500자 이하여야 합니다."
    ) String reason
) {
}

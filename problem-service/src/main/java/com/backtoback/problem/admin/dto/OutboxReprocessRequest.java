package com.backtoback.problem.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 실패 이벤트의 관리자 재처리 사유. */
public record OutboxReprocessRequest(
    @NotBlank(message = "재처리 사유를 입력해 주세요.")
    @Size(
        max = 500,
        message = "재처리 사유는 500자 이하여야 합니다."
    ) String reason
) {
}

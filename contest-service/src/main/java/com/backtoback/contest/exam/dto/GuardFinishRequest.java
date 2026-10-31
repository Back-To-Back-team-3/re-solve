package com.backtoback.contest.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 자원 작업 완료·취소 입력. (API 명세서 §4.7.4)
 */
public record GuardFinishRequest(
    @NotBlank(message = "작업 ID는 필수입니다.")
    @Size(
        max = 100,
        message = "작업 ID는 100자 이하여야 합니다."
    ) String operationId,
    @NotBlank(message = "원천 버전은 필수입니다.")
    @Pattern(
        regexp = "[0-9]+",
        message = "원천 버전은 10진 문자열이어야 합니다."
    ) String sourceVersion,
    @NotBlank(message = "사유는 필수입니다.")
    @Size(
        max = 500,
        message = "사유는 500자 이하여야 합니다."
    ) String reason
) {
}

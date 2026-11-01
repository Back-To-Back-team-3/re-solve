package com.backtoback.contest.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 자원 작업 준비 입력. 자원과 작업 유형의 조합은 서비스에서 확인한다. (API 명세서 §4.7.3)
 */
public record GuardPrepareRequest(
    @NotBlank(message = "작업 ID는 필수입니다.")
    @Size(
        max = 100,
        message = "작업 ID는 100자 이하여야 합니다."
    ) String operationId,
    @NotBlank(message = "작업 유형은 필수입니다.")
    @Pattern(
        regexp = "WITHDRAWAL|STUDY_CLOSE|PROBLEM_HIDE",
        message = "지원하는 작업 유형이 아닙니다."
    ) String operationType,
    @NotBlank(message = "기대 원천 버전은 필수입니다.")
    @Pattern(
        regexp = "[0-9]+",
        message = "기대 원천 버전은 10진 문자열이어야 합니다."
    ) String expectedSourceVersion
) {
}

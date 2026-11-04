package com.backtoback.problem.problem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 검증 코드의 원문을 기본값 치환 없이 전달한다. */
public record ProblemValidationRequest(
    @NotBlank(message = "언어는 필수입니다.")
    @Pattern(
        regexp = "java|python3|cpp",
        message = "지원하지 않는 언어입니다."
    ) String language,
    @NotBlank(message = "코드는 공백일 수 없습니다.") String sourceCode
) {
}

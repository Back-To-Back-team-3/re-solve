package com.backtoback.member.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 진단 Draft 저장 요청 (API 명세서 1.5.4). 코드 크기(64KB)는 {@code CODE_TOO_LARGE}(413)로 응답하도록 서비스에서 확인한다.
 */
public record DiagnosisDraftRequest(
    @NotBlank(message = "언어는 필수입니다.")
    @Pattern(
        regexp = "java|python3|cpp",
        message = "언어는 java, python3, cpp 중 하나입니다."
    ) String language,
    @NotNull(message = "코드는 필수입니다.") String sourceCode,
    @NotBlank(message = "순번은 필수입니다.")
    @Pattern(
        regexp = "[0-9]{1,18}",
        message = "순번은 숫자여야 합니다."
    ) String seq
) {

}

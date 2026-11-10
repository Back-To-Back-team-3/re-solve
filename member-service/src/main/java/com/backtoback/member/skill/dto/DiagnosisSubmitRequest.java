package com.backtoback.member.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 진단 문항 제출 요청 (API 명세서 1.5.5). 지원하지 않는 언어는 {@code UNSUPPORTED_LANGUAGE}, 64KB 초과는
 * {@code CODE_TOO_LARGE}로 응답하도록 서비스에서 확인한다. 공백만인 코드는 받지 않는다(이벤트 계약 M6).
 */
public record DiagnosisSubmitRequest(
    @NotBlank(message = "언어는 필수입니다.") String language,
    @NotBlank(message = "코드를 입력해 주세요.") String sourceCode,
    @NotBlank(message = "순번은 필수입니다.")
    @Pattern(
        regexp = "[0-9]{1,18}",
        message = "순번은 숫자여야 합니다."
    ) String seq
) {

}

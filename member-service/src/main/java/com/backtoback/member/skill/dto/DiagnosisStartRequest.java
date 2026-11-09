package com.backtoback.member.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 진단 시작 요청 (API 명세서 1.5.1). ID는 문자열로 받는다.
 */
public record DiagnosisStartRequest(
    @NotBlank(message = "태그 ID는 필수입니다.")
    @Pattern(
        regexp = "[1-9][0-9]{0,18}",
        message = "태그 ID는 숫자여야 합니다."
    ) String tagId
) {

}

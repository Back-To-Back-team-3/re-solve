package com.backtoback.member.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 태그 진입 요청 (API 명세서 1.4.2). ID는 문자열로 받는다.
 */
public record TagEntryRequest(
    @NotBlank(message = "태그 ID는 필수입니다.")
    @Pattern(
        regexp = "\\d{1,18}",
        message = "태그 ID는 숫자여야 합니다."
    ) String tagId
) {

}

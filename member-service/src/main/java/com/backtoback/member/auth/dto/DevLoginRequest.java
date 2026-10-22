package com.backtoback.member.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 개발용 로그인 요청 (API 명세서 1.1.6). ID는 문자열로 받는다.
 */
public record DevLoginRequest(
    @NotBlank(message = "회원 ID는 필수입니다.")
    @Pattern(
        regexp = "\\d{1,18}",
        message = "회원 ID는 숫자여야 합니다."
    ) String memberId
) {

}

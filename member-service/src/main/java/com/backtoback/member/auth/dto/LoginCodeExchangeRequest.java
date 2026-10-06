package com.backtoback.member.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginCodeExchangeRequest(@NotBlank(message = "로그인 코드는 필수입니다.") String loginCode) {
}

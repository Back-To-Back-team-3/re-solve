package com.backtoback.judge.judging.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 고정 회차의 함수 명세와 통과 언어를 전달한다(API 명세서 §3.3.5). */
public record StarterCodeRequest(
    @NotNull(message = "함수 명세는 필수입니다.") FunctionSpec functionSpec,
    @NotNull(message = "통과 언어 목록은 필수입니다.") List<@NotNull(message = "언어는 필수입니다.") @Pattern(
        regexp = "java|python3|cpp",
        message = "지원하지 않는 언어입니다."
    ) String> languages
) {
}

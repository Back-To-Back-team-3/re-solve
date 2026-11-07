package com.backtoback.contest.exam.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 시험 Run 입력 (API 명세서 §4.2.13). */
public record ExamRunRequest(
    @NotNull(message = "언어를 입력해 주세요.") String language,
    @NotEmpty(message = "코드를 입력해 주세요.") String sourceCode,
    @Size(
        max = 10,
        message = "사용자 테스트는 최대 10개까지 입력할 수 있습니다."
    ) List<@NotNull(message = "사용자 테스트를 입력해 주세요.") @Valid CustomTest> customTests
) {
    /** 사용자 테스트의 함수 인자. */
    public record CustomTest(@NotNull(message = "함수 인자를 입력해 주세요.") List<Object> args) {
    }
}

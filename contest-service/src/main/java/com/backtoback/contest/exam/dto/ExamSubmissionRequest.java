package com.backtoback.contest.exam.dto;

import jakarta.validation.constraints.NotNull;

/** 직접 제출에 필요한 문제, 언어, 코드와 임시 저장 순번이다 (API 명세서 4.2.5). */
public record ExamSubmissionRequest(
    @NotNull(message = "문제 ID는 필수입니다.") String problemId,
    @NotNull(message = "언어는 필수입니다.") String language,
    @NotNull(message = "소스 코드는 필수입니다.") String sourceCode,
    @NotNull(message = "Draft 순번은 필수입니다.") String seq
) {
}

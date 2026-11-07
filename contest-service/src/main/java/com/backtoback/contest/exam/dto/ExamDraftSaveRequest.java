package com.backtoback.contest.exam.dto;

import jakarta.validation.constraints.NotNull;

/** 시험 Draft 저장 입력 (API 명세서 §4.2.4). */
public record ExamDraftSaveRequest(
    @NotNull(message = "언어를 입력해 주세요.") String language,
    @NotNull(message = "코드를 입력해 주세요.") String sourceCode,
    @NotNull(message = "순번을 입력해 주세요.") String seq
) {
}

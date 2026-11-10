package com.backtoback.judge.run.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Contest가 검증한 시험 문맥과 고정 회차의 실행 요청이다. (API 명세서 §3.3.8) */
public record ExamRunRequest(
    @NotBlank String context,
    @NotBlank String contextId,
    @NotBlank String memberId,
    @NotBlank String problemId,
    @NotBlank String problemRevisionId,
    @NotBlank String language,
    @NotBlank String sourceCode,
    @Size(max = 10) List<@NotNull @Valid CustomTest> customTests
) {
    /** 함수 매개변수 순서대로 받은 JSON 인수다. */
    public record CustomTest(@NotNull List<JsonNode> args) {
    }
}

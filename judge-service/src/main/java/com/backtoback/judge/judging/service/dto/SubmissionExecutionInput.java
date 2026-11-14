package com.backtoback.judge.judging.service.dto;

import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/** 원격 호출 전에 읽어 둔 불변 제출 원본과 실행 환경. 외부 제출의 최초 실행 전에는 런타임이 없을 수 있다. */
public record SubmissionExecutionInput(
    long problemId,
    long revisionId,
    String language,
    String sourceCode,
    JudgeRuntime runtime
) {
}

package com.backtoback.judge.judging.dto.response;

import java.math.BigDecimal;

public record JudgeExecutionResultResponse(
    State state,
    int statusId,
    String stdout,
    String stderr,
    String compileOutput,
    String message,
    BigDecimal timeSeconds,
    BigDecimal memoryKiB,
    Integer exitCode,
    Integer exitSignal
) {
    // COMPLETED는 실행 종료를 의미하며 AC/WA 판정은 상위 실행기가 반환값 비교 후 결정한다.
    public enum State {
        QUEUED, RUNNING, COMPLETED, SYSTEM_ERROR
    }
}

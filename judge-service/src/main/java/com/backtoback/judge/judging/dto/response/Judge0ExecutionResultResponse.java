package com.backtoback.judge.judging.dto.response;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Judge0의 실행 상태와 변환 전 원시 결과를 담는다. */
public record Judge0ExecutionResultResponse(
    String token,
    Status status,
    String stdout,
    String stderr,
    @JsonProperty("compile_output") String compileOutput,
    String message,
    BigDecimal time,
    BigDecimal memory,
    @JsonProperty("exit_code") Integer exitCode,
    @JsonProperty("exit_signal") Integer exitSignal
) {
    /**
     * Judge0 실행 상태 식별자다.
     *
     * @param id Judge0 상태 코드
     */
    public record Status(Integer id) {
    }
}

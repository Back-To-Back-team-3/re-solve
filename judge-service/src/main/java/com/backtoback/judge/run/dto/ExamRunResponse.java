package com.backtoback.judge.run.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Contest 내부 계약에 맞춘 실행 결과다. (API 명세서 §3.3.8) */
public record ExamRunResponse(List<Result> results, Instant completedAt) {
    /** 예제와 직접 입력 목록의 순번, 반환값, 오류 및 정수 측정값이다. */
    public record Result(
        String type,
        int index,
        @JsonInclude(JsonInclude.Include.ALWAYS) String output,
        @JsonInclude(JsonInclude.Include.ALWAYS) String error,
        int timeMs,
        int memoryKb
    ) {
    }
}

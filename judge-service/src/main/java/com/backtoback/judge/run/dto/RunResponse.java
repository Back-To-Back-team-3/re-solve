package com.backtoback.judge.run.dto;

import java.math.BigDecimal;
import java.util.List;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 실행 요청 안에서만 사용하는 공개 예제·직접 입력 결과다. (API 명세서 §3.1.8) */
public record RunResponse(List<Result> results, String compileMessage) {
    /** 예제는 파일 ID와 기대값을, 직접 입력은 요청 배열 순번을 사용한다. */
    public record Result(
        String source,
        @JsonInclude(JsonInclude.Include.NON_NULL) String testAssetId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer index,
        @JsonInclude(JsonInclude.Include.ALWAYS) JudgeVerdict verdict,
        String actualReturnText,
        @JsonInclude(JsonInclude.Include.NON_NULL) String expectedReturnText,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer timeMs,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal memoryKb,
        String stdout
    ) {
    }
}

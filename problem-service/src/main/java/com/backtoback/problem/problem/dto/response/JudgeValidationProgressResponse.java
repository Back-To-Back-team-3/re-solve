package com.backtoback.problem.problem.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Judge가 반환하는 현재 시도의 작업 상태와 판정 목록이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeValidationProgressResponse(
    String validationId,
    String problemRevisionId,
    String language,
    String status,
    List<TestResult> tests,
    Integer maxTimeMs,
    String message
) {
    /** Judge가 제공한 고정 순서의 테스트별 판정과 측정값이다. */
    public record TestResult(int testOrder, String verdict, Integer timeMs, Integer memoryKb) {
    }
}

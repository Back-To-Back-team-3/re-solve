package com.backtoback.problem.problem.dto.response;

import java.util.List;
import java.util.Map;

public record ValidationDataResponse(
    String validationId,
    String problemRevisionId,
    String language,
    String requestHash,
    String sourceCode,
    Map<String, Object> functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<ValidationCaseResponse> testCases
) {
}

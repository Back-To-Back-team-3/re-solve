package com.backtoback.problem.problem.dto.response;

import java.util.List;
import java.util.Map;

public record JudgingDataResponse(
    String problemId,
    String problemRevisionId,
    Map<String, Object> functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<String> supportedLanguages,
    List<ExecutionProfileResponse> executionProfiles,
    List<TestCaseReferenceResponse> cases
) {
}

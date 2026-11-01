package com.backtoback.problem.problem.dto.response;

import java.util.List;
import java.util.Map;

public record ProblemContentResponse(
    String problemId,
    String problemRevisionId,
    int revisionNumber,
    String title,
    int difficulty,
    List<ProblemTagResponse> tags,
    String publishedAt,
    String description,
    String constraints,
    String examples,
    Map<String, Object> functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<StarterCodeResponse> starterCodes,
    List<SampleCaseResponse> samples,
    List<String> supportedLanguages
) {
}

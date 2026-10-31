package com.backtoback.problem.problem.dto.response;

import java.util.List;

public record JudgingValidationResponse(
    String problemId,
    String problemRevisionId,
    String scope,
    String visibility,
    int difficulty,
    List<String> tagIds,
    int totalCount,
    List<String> testAssetIds,
    List<String> supportedLanguages,
    List<ExecutionProfileResponse> executionProfiles
) {
}

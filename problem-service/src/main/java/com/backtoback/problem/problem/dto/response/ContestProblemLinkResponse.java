package com.backtoback.problem.problem.dto.response;

public record ContestProblemLinkResponse(
    String problemId,
    String contestId,
    String problemRevisionId,
    int linkedSourceVersion,
    String policyActorId
) {
}

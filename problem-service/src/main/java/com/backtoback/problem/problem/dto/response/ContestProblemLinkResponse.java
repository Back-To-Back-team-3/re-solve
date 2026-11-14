package com.backtoback.problem.problem.dto.response;

public record ContestProblemLinkResponse(
    String problemId,
    String contestId,
    String problemRevisionId,
    int linkedSourceVersion,
    String policyActorId
) {
    /**
     * 영속된 최초 연결을 응답으로 변환한다.
     *
     * @param problem 연결된 문제
     * @return 최초 연결 결과
     */
    public static ContestProblemLinkResponse from(com.backtoback.problem.problem.domain.Problem problem) {
        return new ContestProblemLinkResponse(
            problem.getId().toString(),
            problem.getContestId().toString(),
            problem.getContestProblemRevisionId().toString(),
            problem.getContestLinkSourceVersion(),
            problem.getContestPolicyActorId().toString()
        );
    }
}

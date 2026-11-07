package com.backtoback.problem.problem.dto.response;

/** 새 회차 등록 당시의 식별자·번호·문제 버전. */
public record ProblemRevisionResponse(String problemRevisionId, int revisionNumber, int rowVersion) {
    /**
     * 최초 등록과 공유하는 저장 결과에서 새 회차 응답을 구성한다.
     *
     * @param result 저장 또는 멱등 재생 결과
     * @return 최초 새 회차 응답
     */
    public static ProblemRevisionResponse from(ProblemCreateResponse result) {
        return new ProblemRevisionResponse(result.problemRevisionId(), result.revisionNumber(), result.rowVersion());
    }
}

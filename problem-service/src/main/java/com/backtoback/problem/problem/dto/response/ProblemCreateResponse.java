package com.backtoback.problem.problem.dto.response;

import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.service.ProblemRegistrationService.SaveResult;

/** 최초 저장 시의 문제·회차 식별자와 응답 버전. */
public record ProblemCreateResponse(String problemId, String problemRevisionId, int revisionNumber, int rowVersion) {
    /**
     * 새 저장 결과를 외부 응답으로 변환한다.
     *
     * @param result 같은 트랜잭션에서 확정한 저장 결과
     * @return 최초 등록 응답
     */
    public static ProblemCreateResponse from(SaveResult result) {
        return new ProblemCreateResponse(
            result.problemId().toString(),
            result.problemRevisionId().toString(),
            result.revisionNumber(),
            result.rowVersion()
        );
    }

    /**
     * 현재 문제 버전 대신 회차에 보존한 최초 응답을 복원한다.
     *
     * @param revision 멱등 해시가 일치하는 저장 회차
     * @return 최초 등록 응답
     */
    public static ProblemCreateResponse from(ProblemRevision revision) {
        return new ProblemCreateResponse(
            revision.getProblemId().toString(),
            revision.getId().toString(),
            revision.getRevisionNumber(),
            revision.getResponseRowVersion()
        );
    }
}

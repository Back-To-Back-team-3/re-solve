package com.backtoback.problem.problem.dto.response;

import com.backtoback.problem.problem.domain.Problem;

/** 최초 공개 작업이 확정한 상태와 버전이다. */
public record ProblemPublicationResponse(String visibility, int rowVersion) {
    /**
     * 작업 당시의 성공 결과를 응답으로 변환한다.
     *
     * @param problem 공개로 변경한 문제
     * @return 최초 결과 상태와 버전
     */
    public static ProblemPublicationResponse from(Problem problem) {
        return new ProblemPublicationResponse(problem.getVisibility().name(), problem.getRowVersion());
    }
}

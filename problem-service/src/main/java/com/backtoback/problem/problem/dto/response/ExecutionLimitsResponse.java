package com.backtoback.problem.problem.dto.response;

import com.backtoback.problem.problem.domain.ProblemRevision;

public record ExecutionLimitsResponse(int timeMs, int memoryKb, int wallTimeMs, int outputLimitKb) {
    /**
     * 고정 회차의 실행 제한을 응답으로 변환한다.
     *
     * @param revision 고정 회차
     * @return 회차의 실행 제한
     */
    public static ExecutionLimitsResponse from(ProblemRevision revision) {
        return new ExecutionLimitsResponse(
            revision.getTimeLimitMs(),
            revision.getMemoryLimitKb(),
            revision.getWallTimeLimitMs(),
            revision.getOutputLimitKb()
        );
    }
}

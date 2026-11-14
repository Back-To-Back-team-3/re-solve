package com.backtoback.problem.problem.dto.response;

import com.backtoback.problem.problem.domain.ProblemStateOperation;

/** 상태 작업의 원래 확정 여부와 당시 버전을 반환한다. */
public record ProblemOperationResponse(String operationId, String state, String sourceVersion, String resourceStatus) {
    /**
     * 현재 문제 상태 대신 원래 작업의 스냅샷을 변환한다.
     *
     * @param operation 원래 상태 작업
     * @return 당시 버전·공개 상태
     */
    public static ProblemOperationResponse from(ProblemStateOperation operation) {
        boolean committed = "COMMITTED".equals(operation.getState());
        return new ProblemOperationResponse(
            operation.getOperationId(),
            operation.getState(),
            Integer.toString(committed ? operation.getResultSourceVersion() : operation.getExpectedSourceVersion()),
            committed ? operation.getTargetVisibility() : operation.getBeforeVisibility()
        );
    }
}

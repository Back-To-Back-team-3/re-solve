package com.backtoback.problem.problem.dto.response;

import java.util.List;
import java.util.Map;

public record ValidationDataResponse(
    String validationId,
    String problemRevisionId,
    String language,
    String requestHash,
    String sourceCode,
    Map<String, Object> functionSpec,
    ExecutionLimitsResponse executionLimits,
    List<ValidationCaseResponse> testCases
) {
    /**
     * 고정 DB 참조와 검증한 원문을 내부 조회 응답으로 변환한다.
     *
     * @param snapshot 고정 요청 메타 정보
     * @param code 검증한 코드 원문
     * @param function 고정 함수 명세
     * @param tests 원래 순서의 전체 테스트
     * @return 고정 검증 자료
     */
    public static ValidationDataResponse from(
        com.backtoback.problem.problem.service.ProblemValidationDataStore.Snapshot snapshot,
        String code,
        Map<String, Object> function,
        List<ValidationCaseResponse> tests
    ) {
        return new ValidationDataResponse(
            Long.toString(snapshot.id()),
            Long.toString(snapshot.revisionId()),
            snapshot.language(),
            snapshot.hash(),
            code,
            function,
            snapshot.limits(),
            tests
        );
    }
}

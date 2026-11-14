package com.backtoback.problem.problem.dto.response;

import com.backtoback.problem.problem.domain.ProblemValidation;

/** 검증 접수의 최초 응답은 결과 반영 후에도 REQUESTED로 보존한다. */
public record ProblemValidationResponse(String validationId, String problemRevisionId, String language, String status) {
    /**
     * 저장한 요청을 최초 접수 응답으로 변환한다.
     *
     * @param validation 고정 요청
     * @return 최초 202 응답 데이터
     */
    public static ProblemValidationResponse from(ProblemValidation validation) {
        return new ProblemValidationResponse(
            validation.getId().toString(),
            validation.getProblemRevisionId().toString(),
            validation.getLanguage(),
            "REQUESTED"
        );
    }
}

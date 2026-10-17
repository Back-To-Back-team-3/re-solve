package com.backtoback.judge.global.api;

import java.util.List;

import com.backtoback.judge.global.exception.ErrorCode;

public record ApiErrorResponse(boolean success, ErrorBody error) {
    /**
     * 오류 코드와 해당 코드의 기본 메시지로 공통 오류 응답을 생성한다.
     *
     * @param code 응답할 오류 코드
     * @return 공통 형식의 실패 응답
     */
    public static ApiErrorResponse of(ErrorCode code) {
        return of(code, code.getMessage(), List.of());
    }

    /**
     * 오류 코드와 메시지로 공통 오류 응답을 생성한다.
     *
     * @param code 응답할 오류 코드
     * @param message 사용자에게 전달할 오류 메시지
     * @param details 필드별 검증 오류 목록
     * @return 공통 형식의 실패 응답
     */
    public static ApiErrorResponse of(ErrorCode code, String message, List<FieldError> details) {
        return new ApiErrorResponse(false, new ErrorBody(code.name(), message, details));
    }

    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    public record FieldError(String field, String reason) {
    }
}

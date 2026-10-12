package com.backtoback.judge.global.api;

import java.util.List;

import com.backtoback.judge.global.exception.ErrorCode;

public record ApiErrorResponse(boolean success, ErrorBody error) {
    /**
     * 공통 오류 응답 형식 (API 명세서 0.3).
     */
    public static ApiErrorResponse of(ErrorCode code) {
        return of(code, code.getMessage(), List.of());
    }

    public static ApiErrorResponse of(ErrorCode code, String message, List<FieldError> details) {
        return new ApiErrorResponse(false, new ErrorBody(code.name(), message, details));
    }

    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    public record FieldError(String field, String reason) {
    }
}

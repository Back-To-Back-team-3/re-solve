package com.backtoback.problem.global.api;

import java.util.List;

public record ApiErrorResponse(boolean success, ErrorBody error) {
    /**
     * 공통 성공 응답 형식 (API 명세서 0.3).
     */
    public static ApiErrorResponse of(String code, String message) {
        return of(code, message, List.of());
    }

    public static ApiErrorResponse of(String code, String message, List<FieldError> details) {
        return new ApiErrorResponse(false, new ErrorBody(code, message, details));
    }

    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    public record FieldError(String field, String reason) {
    }
}

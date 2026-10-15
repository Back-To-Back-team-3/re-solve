package com.backtoback.member.global.response;

import java.util.List;

import com.backtoback.member.global.error.ErrorCode;

/**
 * 공통 실패 응답 형식 (API 명세서 0.3). 프론트엔드는 {@code error.code}로 분기한다.
 */
public record ErrorResponse(boolean success, ErrorBody error) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> details) {
        return new ErrorResponse(false, new ErrorBody(errorCode.name(), errorCode.getMessage(), details));
    }

    public record ErrorBody(String code, String message, List<FieldErrorDetail> details) {
    }

    public record FieldErrorDetail(String field, String reason) {
    }
}

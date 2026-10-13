package com.backtoback.gateway.dto;

import java.util.List;

/**
 * 게이트웨이 공통 에러 응답 DTO (API 명세서 0.3 규약).
 */
public record ErrorResponse(boolean success, ErrorBody error) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(false, new ErrorBody(code, message, List.of()));
    }

    public record ErrorBody(String code, String message, List<Object> details) {
    }
}

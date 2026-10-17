package com.backtoback.problem.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {
    COMMON_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청을 확인해 주세요."),
    COMMON_INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 정보가 없거나 유효하지 않습니다."),
    AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");

    private final HttpStatus status;
    private final String message;

    /**
     * 오류 코드의 HTTP 상태와 사용자 안내 메시지를 설정한다.
     *
     * @param status 응답 HTTP 상태
     * @param message 사용자에게 전달할 오류 메시지
     */
    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

}

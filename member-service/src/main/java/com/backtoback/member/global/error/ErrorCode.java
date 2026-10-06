package com.backtoback.member.global.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 오류 코드 (API 명세서 0.4). 응답의 {@code error.code}에는 상수 이름을 그대로 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    COMMON_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    COMMON_INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

    // Auth
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않습니다."),
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "보안을 위해 모든 기기에서 로그아웃되었습니다. 다시 로그인해 주세요."),
    AUTH_LOGIN_CODE_INVALID(HttpStatus.UNAUTHORIZED, "로그인 코드가 없거나 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_OAUTH_FAILED(HttpStatus.BAD_GATEWAY, "GitHub 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}

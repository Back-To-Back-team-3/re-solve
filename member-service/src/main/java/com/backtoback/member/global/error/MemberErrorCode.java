package com.backtoback.member.global.error;

import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.ErrorCode;

import lombok.Getter;

/**
 * member-service 전용 오류 코드 (API 명세서 0.4 Auth·Member). 공통 오류는 {@code CommonErrorCode}를 쓴다.
 */
@Getter
public enum MemberErrorCode implements ErrorCode {

    // Auth
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "보안을 위해 모든 기기에서 로그아웃되었습니다. 다시 로그인해 주세요."),
    AUTH_LOGIN_CODE_INVALID(HttpStatus.UNAUTHORIZED, "로그인 코드가 없거나 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_OAUTH_DENIED(HttpStatus.UNAUTHORIZED, "GitHub 로그인이 취소되었습니다."),
    AUTH_OAUTH_FAILED(HttpStatus.BAD_GATEWAY, "GitHub 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요."),

    // Member
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원은 이용할 수 없습니다."),
    MEMBER_NICKNAME_INVALID(HttpStatus.BAD_REQUEST, "닉네임은 이모지 없이 2~50자로 입력해 주세요."),

    // Level
    LEVEL_TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다."),
    LEVEL_ALREADY_ENTERED(HttpStatus.CONFLICT, "이미 시작한 태그입니다.");

    private final HttpStatus status;
    private final String message;

    MemberErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String getCode() {
        return name();
    }
}

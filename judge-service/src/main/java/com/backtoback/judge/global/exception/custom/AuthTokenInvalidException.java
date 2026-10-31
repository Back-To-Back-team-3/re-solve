package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

/** 탈퇴 회원 등 인증 정보가 유효하지 않음을 나타낸다. */
public final class AuthTokenInvalidException extends BusinessException {
    /** {@code AUTH_TOKEN_INVALID} 오류 코드를 고정하여 예외를 생성한다. */
    public AuthTokenInvalidException() {
        super(CommonErrorCode.AUTH_TOKEN_INVALID);
    }
}

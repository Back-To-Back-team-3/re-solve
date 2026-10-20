package com.backtoback.problem.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

public final class AuthTokenInvalidException extends BusinessException {
    /**
     * 인증 정보가 없거나 유효하지 않음을 나타내는 예외를 생성한다.
     */
    public AuthTokenInvalidException() {
        super(CommonErrorCode.AUTH_TOKEN_INVALID);
    }
}

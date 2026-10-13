package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class AuthAccessDeniedException extends BusinessException {
    /**
     * 접근 권한 부족을 나타내는 예외를 생성한다.
     */
    public AuthAccessDeniedException() {
        super(ErrorCode.AUTH_ACCESS_DENIED);
    }
}

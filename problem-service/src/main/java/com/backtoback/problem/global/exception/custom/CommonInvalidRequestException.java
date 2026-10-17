package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class CommonInvalidRequestException extends BusinessException {
    /**
     * 요청값이 유효하지 않음을 나타내는 예외를 생성한다.
     */
    public CommonInvalidRequestException() {
        super(ErrorCode.COMMON_INVALID_REQUEST);
    }
}

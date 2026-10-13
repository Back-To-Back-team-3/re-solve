package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class CommonInternalServerErrorException extends BusinessException {
    /**
     * 서버 내부 오류를 나타내는 예외를 생성한다.
     */
    public CommonInternalServerErrorException() {
        super(ErrorCode.COMMON_INTERNAL_SERVER_ERROR);
    }
}

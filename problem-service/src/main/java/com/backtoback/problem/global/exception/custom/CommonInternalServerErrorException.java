package com.backtoback.problem.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

public final class CommonInternalServerErrorException extends BusinessException {
    /**
     * 서버 내부 오류를 나타내는 예외를 생성한다.
     */
    public CommonInternalServerErrorException() {
        super(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR);
    }
}

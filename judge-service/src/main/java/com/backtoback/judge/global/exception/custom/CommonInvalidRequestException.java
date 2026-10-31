package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

/** 요청 형식이나 필수 값이 유효하지 않음을 나타낸다. */
public final class CommonInvalidRequestException extends BusinessException {
    /** {@code COMMON_INVALID_REQUEST} 오류 코드를 고정하여 예외를 생성한다. */
    public CommonInvalidRequestException() {
        super(CommonErrorCode.COMMON_INVALID_REQUEST);
    }
}

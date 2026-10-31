package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

/** 필수 검증 의존성을 조회하거나 신뢰할 수 없음을 나타낸다. */
public final class CommonDependencyUnavailableException extends BusinessException {
    /** {@code COMMON_DEPENDENCY_UNAVAILABLE} 오류 코드를 고정하여 예외를 생성한다. */
    public CommonDependencyUnavailableException() {
        super(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }
}

package com.backtoback.integration.global.error;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.ErrorCode;

/**
 * integration-service의 비즈니스 예외.
 * 공통 {@code GlobalExceptionHandler}가 오류 코드의 상태와 공통 오류 응답으로 변환한다.
 */
public class IntegrationException extends BusinessException {

    public IntegrationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public IntegrationException(ErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}

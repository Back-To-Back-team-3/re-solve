package com.backtoback.study.global.error;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.ErrorCode;

/**
 * study-service의 비즈니스 예외. 공통 {@code GlobalExceptionHandler}가 오류 코드의 상태와 공통 오류 응답으로 변환한다.
 */
public class StudyException extends BusinessException {

    public StudyException(ErrorCode errorCode) {
        super(errorCode);
    }

    public StudyException(ErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}

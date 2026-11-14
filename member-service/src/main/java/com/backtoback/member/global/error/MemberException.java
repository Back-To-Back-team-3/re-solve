package com.backtoback.member.global.error;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.ErrorCode;

/**
 * member-service의 비즈니스 예외. 공통 {@code GlobalExceptionHandler}가 오류 코드의 상태와 공통 오류 응답으로 변환한다.
 */
public class MemberException extends BusinessException {

    public MemberException(ErrorCode errorCode) {
        super(errorCode);
    }

    public MemberException(ErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}

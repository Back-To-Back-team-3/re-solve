package com.backtoback.problem.global.exception;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.ErrorCode;

/**
 * 공통 또는 문제 서비스 오류 코드를 공통 예외 처리기에 전달한다. (컨벤션·빌드 매뉴얼 6.3 응답·예외)
 */
public final class ProblemException extends BusinessException {
    public ProblemException(ErrorCode errorCode) {
        super(errorCode);
    }
}

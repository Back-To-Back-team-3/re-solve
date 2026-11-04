package com.backtoback.contest.global.error;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.ErrorCode;

/**
 * Contest 업무 오류를 공통 오류 처리기에 전달한다. (컨벤션·빌드 매뉴얼 §6.3)
 */
public class ContestException extends BusinessException {
    public ContestException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 오류 코드와 함께 내부 실패 원인을 보존한다.
     *
     * @param errorCode 공통 또는 도메인 오류 코드
     * @param cause 내부 실패 원인
     */
    public ContestException(ErrorCode errorCode, Throwable cause) {
        super(errorCode);
        initCause(cause);
    }
}

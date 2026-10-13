package com.backtoback.problem.global.exception;

import lombok.Getter;

@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * 전용 예외가 사용할 오류 코드와 기본 메시지를 설정한다.
     *
     * @param errorCode 응답 상태와 메시지를 정의한 오류 코드
     */
    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}

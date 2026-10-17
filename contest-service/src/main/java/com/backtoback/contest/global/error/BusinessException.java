package com.backtoback.contest.global.error;

import lombok.Getter;

/**
 * 서비스 계약에서 명세 오류를 전달한다. HTTP 변환은 공통 예외 처리기가 담당한다.
 * 도메인 예외가 상속하며, 공통 인증·입력 오류는 해당 코드로 직접 전달할 수 있다.
 */
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * 오류 코드의 안전한 안내 문구를 예외 메시지로 설정한다.
     *
     * @param errorCode 호출자에게 반환할 명세 오류
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}

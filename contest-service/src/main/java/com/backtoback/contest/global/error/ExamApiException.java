package com.backtoback.contest.global.error;

import lombok.Getter;

/**
 * 서비스 계약에서 명세 오류를 전달한다. HTTP 변환은 공통 예외 처리기가 담당한다.
 * stub은 지정된 오류 예시를 이 예외로 전달하며 실제 권한·DB 판정을 수행하지 않는다.
 */
@Getter
public class ExamApiException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * 오류 코드의 안전한 안내 문구를 예외 메시지로 설정한다.
     *
     * @param errorCode 호출자에게 반환할 명세 오류
     */
    public ExamApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}

package com.backtoback.judge.global.exception;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

import lombok.Getter;

/**
 * 채점 서비스의 업무 오류와 공개하지 않을 외부 HTTP 상태를 보관한다.
 */
@Getter
public class JudgeException extends BusinessException {
    private final Integer upstreamStatus;

    /**
     * 채점 오류 코드로 공통 오류 처리기에 전달할 예외를 생성한다.
     *
     * @param errorCode 채점 오류 코드
     */
    public JudgeException(JudgeErrorCode errorCode) {
        this(errorCode, null);
    }

    /**
     * 채점 오류 코드와 내부 진단용 HTTP 상태로 예외를 생성한다.
     * 원본 응답 본문과 인증 정보를 보관하지 않으며 HTTP 상태는 공통 응답에 포함하지 않는다.
     *
     * @param errorCode 채점 오류 코드
     * @param upstreamStatus 외부 HTTP 상태; 확인할 수 없으면 null
     */
    public JudgeException(JudgeErrorCode errorCode, Integer upstreamStatus) {
        super(errorCode);
        this.upstreamStatus = upstreamStatus;
    }
}

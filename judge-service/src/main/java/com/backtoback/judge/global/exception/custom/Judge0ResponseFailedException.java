package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.exception.JudgeErrorCode;

import lombok.Getter;

@Getter
public class Judge0ResponseFailedException extends BusinessException {
    private final Integer upstreamStatus;

    /**
     * Judge0의 HTTP 오류 또는 올바르지 않은 응답을 전달한다.
     * 코드·인증 정보를 포함할 수 있는 원본 응답 본문과 HTTP 예외는 보관하지 않는다.
     *
     * @param upstreamStatus 외부 HTTP 상태; 확인할 수 없으면 null
     */
    public Judge0ResponseFailedException(Integer upstreamStatus) {
        super(JudgeErrorCode.JUDGE0_RESPONSE_FAILED);
        this.upstreamStatus = upstreamStatus;
    }
}

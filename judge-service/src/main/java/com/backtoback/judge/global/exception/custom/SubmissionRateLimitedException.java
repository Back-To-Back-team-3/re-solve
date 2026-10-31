package com.backtoback.judge.global.exception.custom;

import org.springframework.http.HttpHeaders;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 제출 요청 한도를 초과함을 나타낸다. */
public final class SubmissionRateLimitedException extends BusinessException {
    /**
     * 요청 제한 오류와 계산한 재시도 대기 헤더를 보관한다.
     *
     * @param headers 공개할 {@code Retry-After} 응답 헤더
     */
    public SubmissionRateLimitedException(HttpHeaders headers) {
        super(JudgeErrorCode.SUBMISSION_RATE_LIMITED, headers);
    }
}

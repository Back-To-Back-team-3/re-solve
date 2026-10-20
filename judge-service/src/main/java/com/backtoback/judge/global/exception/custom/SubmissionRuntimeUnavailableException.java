package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.exception.JudgeErrorCode;

public class SubmissionRuntimeUnavailableException extends BusinessException {
    /**
     * 기존 실행 환경이 없거나 저장한 정보와 달라 재채점할 수 없음을 전달한다.
     */
    public SubmissionRuntimeUnavailableException() {
        super(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE);
    }
}

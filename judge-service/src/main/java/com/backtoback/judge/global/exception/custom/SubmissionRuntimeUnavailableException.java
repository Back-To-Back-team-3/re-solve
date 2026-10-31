package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 고정 실행 환경을 사용할 수 없음을 나타낸다. */
public final class SubmissionRuntimeUnavailableException extends BusinessException {
    /** {@code SUBMISSION_RUNTIME_UNAVAILABLE} 오류 코드를 고정하여 예외를 생성한다. */
    public SubmissionRuntimeUnavailableException() {
        super(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE);
    }
}

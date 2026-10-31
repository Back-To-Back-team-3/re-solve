package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 일반 풀이로 제출할 수 없는 문제를 나타낸다. */
public final class ProblemNotAvailableException extends BusinessException {
    /** {@code PROBLEM_NOT_AVAILABLE} 오류 코드를 고정하여 예외를 생성한다. */
    public ProblemNotAvailableException() {
        super(JudgeErrorCode.PROBLEM_NOT_AVAILABLE);
    }
}

package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class ProblemTagInUseException extends BusinessException {
    /**
     * 문제 회차에서 태그를 사용 중임을 나타내는 예외를 생성한다.
     */
    public ProblemTagInUseException() {
        super(ErrorCode.PROBLEM_TAG_IN_USE);
    }
}

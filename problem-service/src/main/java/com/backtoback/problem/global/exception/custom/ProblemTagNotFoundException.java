package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class ProblemTagNotFoundException extends BusinessException {
    /**
     * 요청한 태그가 없음을 나타내는 예외를 생성한다.
     */
    public ProblemTagNotFoundException() {
        super(ErrorCode.PROBLEM_TAG_NOT_FOUND);
    }
}

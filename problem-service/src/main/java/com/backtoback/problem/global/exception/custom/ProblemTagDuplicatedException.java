package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class ProblemTagDuplicatedException extends BusinessException {
    /**
     * 태그 이름 중복을 나타내는 예외를 생성한다.
     */
    public ProblemTagDuplicatedException() {
        super(ErrorCode.PROBLEM_TAG_DUPLICATED);
    }
}

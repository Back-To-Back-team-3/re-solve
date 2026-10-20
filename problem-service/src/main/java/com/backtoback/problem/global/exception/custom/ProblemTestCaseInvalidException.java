package com.backtoback.problem.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.problem.global.exception.ProblemErrorCode;

public final class ProblemTestCaseInvalidException extends BusinessException {
    /**
     * 테스트 데이터가 함수 명세 또는 테스트 구성 정책에 맞지 않음을 나타내는 예외를 생성한다.
     */
    public ProblemTestCaseInvalidException() {
        super(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
    }
}

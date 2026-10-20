package com.backtoback.problem.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.problem.global.exception.ProblemErrorCode;

public final class ProblemVersionConflictException extends BusinessException {
    /**
     * 변경 충돌로 재시도가 필요함을 나타내는 예외를 생성한다.
     */
    public ProblemVersionConflictException() {
        super(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
    }
}

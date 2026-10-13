package com.backtoback.problem.global.exception.custom;

import com.backtoback.problem.global.exception.BusinessException;
import com.backtoback.problem.global.exception.ErrorCode;

public final class IdempotencyKeyConflictException extends BusinessException {
    /**
     * 유효한 멱등키에 다른 요청 본문이 전달되었음을 나타내는 예외를 생성한다.
     */
    public IdempotencyKeyConflictException() {
        super(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
}

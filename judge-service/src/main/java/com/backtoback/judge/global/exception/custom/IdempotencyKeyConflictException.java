package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

/** 같은 멱등 키에 다른 요청 본문이 사용됨을 나타낸다. */
public final class IdempotencyKeyConflictException extends BusinessException {
    /** {@code IDEMPOTENCY_KEY_CONFLICT} 오류 코드를 고정하여 예외를 생성한다. */
    public IdempotencyKeyConflictException() {
        super(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
}

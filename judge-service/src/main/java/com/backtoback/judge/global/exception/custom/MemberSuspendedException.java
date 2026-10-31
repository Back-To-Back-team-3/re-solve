package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 정지 회원의 쓰기 요청을 나타낸다. */
public final class MemberSuspendedException extends BusinessException {
    /** {@code MEMBER_SUSPENDED} 오류 코드를 고정하여 예외를 생성한다. */
    public MemberSuspendedException() {
        super(JudgeErrorCode.MEMBER_SUSPENDED);
    }
}

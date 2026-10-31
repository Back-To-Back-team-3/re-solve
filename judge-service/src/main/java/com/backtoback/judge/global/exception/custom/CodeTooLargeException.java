package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 제출 코드가 UTF-8 64KiB를 초과함을 나타낸다. */
public final class CodeTooLargeException extends BusinessException {
    /** {@code CODE_TOO_LARGE} 오류 코드를 고정하여 예외를 생성한다. */
    public CodeTooLargeException() {
        super(JudgeErrorCode.CODE_TOO_LARGE);
    }
}

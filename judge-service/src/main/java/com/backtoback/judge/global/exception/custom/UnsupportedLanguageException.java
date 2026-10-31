package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 요청 언어가 지원되지 않음을 나타낸다. */
public final class UnsupportedLanguageException extends BusinessException {
    /** {@code UNSUPPORTED_LANGUAGE} 오류 코드를 고정하여 예외를 생성한다. */
    public UnsupportedLanguageException() {
        super(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
    }
}

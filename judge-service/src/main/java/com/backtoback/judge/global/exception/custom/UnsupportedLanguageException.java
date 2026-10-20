package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.exception.JudgeErrorCode;

public class UnsupportedLanguageException extends BusinessException {
    /**
     * 지원하지 않거나 활성화되지 않은 언어의 실행 요청을 거부한다.
     */
    public UnsupportedLanguageException() {
        super(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
    }
}

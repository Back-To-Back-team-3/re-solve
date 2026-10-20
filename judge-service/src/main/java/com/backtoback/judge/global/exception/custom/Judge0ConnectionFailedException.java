package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.exception.JudgeErrorCode;

public class Judge0ConnectionFailedException extends BusinessException {
    /**
     * Judge0 연결 실패 또는 통신 시간 초과를 전달한다.
     */
    public Judge0ConnectionFailedException() {
        super(JudgeErrorCode.JUDGE0_CONNECTION_FAILED);
    }
}

package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.exception.JudgeErrorCode;

public class Judge0ConfigurationInvalidException extends BusinessException {
    /**
     * Judge0 실행에 필요한 서버 주소가 설정되지 않았음을 전달한다.
     */
    public Judge0ConfigurationInvalidException() {
        super(JudgeErrorCode.JUDGE0_CONFIGURATION_INVALID);
    }
}

package com.backtoback.study.hint.exception;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

public class HintDailyLimitExceededException extends StudyException {

    public HintDailyLimitExceededException() {
        super(StudyErrorCode.HINT_DAILY_LIMIT_EXCEEDED);
    }
}

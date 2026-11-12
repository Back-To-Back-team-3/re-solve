package com.backtoback.study.hint.exception;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

public class HintInvalidStatusTransitionException extends StudyException {

    public HintInvalidStatusTransitionException() {
        super(StudyErrorCode.HINT_INVALID_STATUS_TRANSITION);
    }
}

package com.backtoback.study.hint.exception;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

public class HintBlockedDuringExamException extends StudyException {

    public HintBlockedDuringExamException() {
        super(StudyErrorCode.HINT_BLOCKED_DURING_EXAM);
    }
}

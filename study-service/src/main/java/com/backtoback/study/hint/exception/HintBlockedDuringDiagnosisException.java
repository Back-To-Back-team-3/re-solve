package com.backtoback.study.hint.exception;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

public class HintBlockedDuringDiagnosisException extends StudyException {

    public HintBlockedDuringDiagnosisException() {
        super(StudyErrorCode.HINT_BLOCKED_DURING_DIAGNOSIS);
    }
}

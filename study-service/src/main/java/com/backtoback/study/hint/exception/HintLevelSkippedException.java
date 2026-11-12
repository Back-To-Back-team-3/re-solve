package com.backtoback.study.hint.exception;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

public class HintLevelSkippedException extends StudyException {

    public HintLevelSkippedException() {
        super(StudyErrorCode.HINT_LEVEL_SKIPPED);
    }
}

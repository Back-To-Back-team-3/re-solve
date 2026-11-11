package com.backtoback.study.hint.exception;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;

public class HintProblemNotAvailableException extends StudyException {

    public HintProblemNotAvailableException() {
        super(StudyErrorCode.HINT_PROBLEM_NOT_AVAILABLE);
    }
}

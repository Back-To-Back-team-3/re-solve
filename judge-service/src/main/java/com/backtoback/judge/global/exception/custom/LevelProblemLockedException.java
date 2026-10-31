package com.backtoback.judge.global.exception.custom;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.error.JudgeErrorCode;

/** 회원의 태그 레벨이 문제 난이도에 미달함을 나타낸다. */
public final class LevelProblemLockedException extends BusinessException {
    /** {@code LEVEL_PROBLEM_LOCKED} 오류 코드를 고정하여 예외를 생성한다. */
    public LevelProblemLockedException() {
        super(JudgeErrorCode.LEVEL_PROBLEM_LOCKED);
    }
}

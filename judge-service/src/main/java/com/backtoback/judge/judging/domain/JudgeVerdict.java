package com.backtoback.judge.judging.domain;

/**
 * 테스트 한 건의 채점 판정이다. {@code SYSTEM_ERROR}는 사용자 판정이 아닌 재시도 대상이다. (정책 및 상태 §3.3)
 */
public enum JudgeVerdict {
    AC, WA, CE, RE, TLE, MLE, SYSTEM_ERROR
}

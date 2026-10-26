package com.backtoback.study.replica.domain;

/**
 * 채점 판정(ERD 4.5.9 member_ac_replicas.verdict). judge-service가 발행하는 값과 같은 집합이며,
 * 서비스 간 클래스를 공유하지 않는 기준(erd.md 공통 컬럼 규칙)에 따라 study-service에 별도로 둔다.
 */
public enum JudgeVerdict {
    AC, WA, TLE, MLE, RE, CE
}

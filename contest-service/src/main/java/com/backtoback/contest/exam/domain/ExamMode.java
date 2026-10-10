package com.backtoback.contest.exam.domain;

/**
 * 시험 진행 방식을 표현한다.
 * 상태 전이나 시간 계산을 수행하지 않으며 WINDOW 값의 보존이 해당 기능의 구현 완료를 뜻하지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams): 진행 방식과 개인 제한 시간의 구분</li>
 * </ul>
 */
public enum ExamMode {
    // 시험에 정해진 공통 시작·종료 시간을 사용하는 방식이다.
    FIXED,

    // 허용된 기간 안에서 개인 제한 시간을 사용하는 후속 P3 방식이다.
    WINDOW
}

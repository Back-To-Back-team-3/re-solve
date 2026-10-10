package com.backtoback.contest.exam.domain;

/**
 * 시험 전체의 진행 상태를 표현한다.
 * 전이 가능 조건은 서비스에서 검증하며 enum 자체가 상태 전이를 수행하지는 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams): 시험 전체의 상태와 전이 구분</li>
 * </ul>
 */
public enum ExamStatus {
    // 시작 전 상태이며 문서상 수정·취소가 가능한 단계다.
    SCHEDULED,

    // 시험이 시작되어 진행 중인 상태다.
    IN_PROGRESS,

    // 시험은 종료됐지만 결과는 아직 잠정 상태인 단계다.
    CLOSED,

    // 모든 제출이 종결되어 결과가 확정된 상태다. 관리자 재확정 후에도 이 상태를 유지한다.
    FINALIZED,

    // 시험이 취소된 종단 상태이며 시험 행을 삭제한다는 뜻은 아니다.
    CANCELED
}

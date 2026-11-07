package com.backtoback.contest.exam.domain;

/**
 * 시험 참가의 등록·입장·종료 상태를 구분한다.
 * <p>기준 문서</p>
 * <ul><li>도메인 및 데이터베이스 v1.0 / §4.4.3 시험 참가자: 참가 상태와 허용 전이</li></ul>
 */
public enum ExamParticipantStatus {
    REGISTERED,
    STARTED,
    FINISHED,
    // 전체 종료 시까지 입장하지 않은 상태다.
    ABSENT,
    // 시험 시작 전에 참가를 취소한 상태다.
    CANCELED
}

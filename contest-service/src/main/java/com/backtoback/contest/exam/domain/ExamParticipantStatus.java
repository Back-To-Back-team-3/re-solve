package com.backtoback.contest.exam.domain;

/**
 * 시험 참가자의 진행 상태를 표현한다.
 * 시험 전체 상태와 구분하며 각 전이 조건·시간 기록은 후속 서비스에서 처리한다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.3 시험 참가자 (exam_participants): 참가 상태와 종단 상태 구분</li>
 * </ul>
 */
public enum ExamParticipantStatus {
    // 참가 등록만 완료한 상태이며 아직 최초 입장 시간은 없다.
    REGISTERED,

    // 시험에 최초 입장하여 참가를 시작한 상태다.
    STARTED,

    // 입장한 참가자의 응시가 종료된 종단 상태다.
    FINISHED,

    // 등록했지만 입장하지 않은 채 시험이 종료된 종단 상태다.
    ABSENT,

    // 시작 전 참가를 취소한 종단 상태다. 재신청은 기존 행 복원이 아닌 새 참가 행으로 처리한다.
    CANCELED
}

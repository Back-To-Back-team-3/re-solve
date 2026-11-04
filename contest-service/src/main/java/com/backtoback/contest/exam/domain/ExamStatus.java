package com.backtoback.contest.exam.domain;

/**
 * 시험의 진행 상태를 나타낸다 (ERD 4.4.1 exams).
 */
public enum ExamStatus {
    SCHEDULED, IN_PROGRESS, CLOSED, FINALIZED, CANCELED
}

package com.backtoback.notification.reminder.domain;

/**
 * 시험 시작 임박 예약 알림의 상태 (스케줄러 명세서 7.1).
 */
public enum ReminderStatus {

    /**
     * 발화 대기 중.
     */
    SCHEDULED,

    /**
     * 발화 완료 (알림 생성 또는 수신 거부로 인한 처리 완료 포함).
     */
    SENT,

    /**
     * 참가 취소·시험 취소 또는 시작 시각 경과로 무효화됨.
     */
    CANCELED
}

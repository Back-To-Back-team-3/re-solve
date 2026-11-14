package com.backtoback.integration.infra.event.domain;

/**
 * Outbox 이벤트 발행 상태 (erd.md > 3.1, scheduler.md > 0.2.1).
 */
public enum OutboxEventStatus {
    /**
     * 발행 대기 중
     */
    PENDING,

    /**
     * 발행 완료
     */
    PUBLISHED,

    /**
     * 최대 재시도 초과로 발행 실패
     */
    FAILED
}

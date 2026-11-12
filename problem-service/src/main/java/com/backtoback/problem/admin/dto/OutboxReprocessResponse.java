package com.backtoback.problem.admin.dto;

/** 재처리를 접수한 서비스 소유 Outbox 식별자. */
public record OutboxReprocessResponse(String outboxEventId) {

    /**
     * Outbox 식별자를 문자열 응답으로 변환한다.
     *
     * @param outboxEventId 재처리한 행 식별자
     * @return 문자열 식별자 응답
     */
    public static OutboxReprocessResponse from(long outboxEventId) {
        return new OutboxReprocessResponse(Long.toString(outboxEventId));
    }
}

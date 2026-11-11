package com.backtoback.problem.admin.dto;

public record OutboxReprocessResponse(String outboxEventId) {

    public static OutboxReprocessResponse from(long outboxEventId) {
        return new OutboxReprocessResponse(Long.toString(outboxEventId));
    }
}

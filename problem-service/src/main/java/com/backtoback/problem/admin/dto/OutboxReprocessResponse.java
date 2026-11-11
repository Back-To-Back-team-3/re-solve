package com.backtoback.problem.admin.dto;

public record OutboxReprocessResponse(String outboxEventId) {

    public static OutboxReprocessResponse of(long outboxEventId) {
        return new OutboxReprocessResponse(Long.toString(outboxEventId));
    }
}

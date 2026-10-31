package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 실제 입장한 미확정 문맥 목록. 단순 등록·ABSENT는 포함하지 않는다. (API 명세서 §4.7.2)
 */
public record ActiveParticipationResponse(boolean withdrawalBlocked, List<Item> items) {
    /**
     * 실제 입장 시각과 참가 상태.
     */
    public record Item(
        String context,
        String contextId,
        String participantId,
        OffsetDateTime enteredAt,
        String status
    ) {
    }
}

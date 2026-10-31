package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 예정·진행 중인 고정 문제 사용 목록. 상태 변경 승인을 대신하지 않는다. (API 명세서 §4.7.6)
 */
public record ProblemUsageResponse(List<Item> items, boolean hasBlockingUsage) {
    /**
     * 시험·대회의 고정 문제 회차와 전체 시간.
     */
    public record Item(
        String context,
        String contextId,
        String problemRevisionId,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
    }
}

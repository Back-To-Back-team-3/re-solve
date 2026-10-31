package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 활성 등록자에 대한 힌트 차단 복구 페이지. 개인 종료만으로 제외하지 않는다. (API 명세서 §4.7.7)
 */
public record HintBlockResponse(List<Item> items, String nextCursor, boolean hasMore) {
    /**
     * 문맥·참가 버전과 고정 문제 ID 목록.
     */
    public record Item(
        String context,
        String contextId,
        String participantId,
        String memberId,
        String participantRevision,
        String contextRevision,
        List<String> problemIds,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
    }
}

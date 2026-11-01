package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 스터디 시험 상태의 커서 페이지. CLOSED와 FINALIZED를 구분한다. (API 명세서 §4.7.5)
 */
public record StudyExamResponse(List<Item> items, String nextCursor, boolean hasMore) {
    /**
     * 시험의 전체 시작·종료 시각과 설정 버전.
     */
    public record Item(
        String examId,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String examRevision
    ) {
    }
}

package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.backtoback.contest.exam.domain.Exam;

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
        /** DB의 서울 시각과 시험 상태를 내부 API 응답으로 변환한다. (API 명세서 §0.1·§4.7.5) */
        public static Item from(Exam exam) {
            var seoul = ZoneId.of("Asia/Seoul");
            return new Item(
                exam.getId().toString(),
                exam.getStatus().name(),
                exam.getStartsAt().atZone(seoul).toOffsetDateTime(),
                exam.getEndsAt().atZone(seoul).toOffsetDateTime(),
                Integer.toString(exam.getExamRevision())
            );
        }
    }
}

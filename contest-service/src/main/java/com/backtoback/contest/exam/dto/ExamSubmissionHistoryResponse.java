package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.contest.exam.service.ExamSubmissionHistoryService;

/** 본인 제출 이력 페이지다 (API 명세서 4.2.9). */
public record ExamSubmissionHistoryResponse(List<Item> items, String nextCursor, boolean hasMore) {
    public static ExamSubmissionHistoryResponse from(ExamSubmissionHistoryService.Page result) {
        return new ExamSubmissionHistoryResponse(
            result.items().stream().map(Item::from).toList(),
            result.nextCursor(),
            result.hasMore()
        );
    }

    public record Item(
        String examSubmissionId,
        String problemId,
        String draftSeq,
        String submissionType,
        OffsetDateTime receivedAt,
        String status,
        String verdict,
        String score,
        Integer passedCount,
        Integer totalCount
    ) {
        static Item from(ExamSubmissionHistoryService.Item result) {
            return new Item(
                result.examSubmissionId(),
                result.problemId(),
                result.draftSeq(),
                result.submissionType(),
                result.receivedAt(),
                result.status(),
                result.verdict(),
                result.score(),
                result.passedCount(),
                result.totalCount()
            );
        }
    }
}

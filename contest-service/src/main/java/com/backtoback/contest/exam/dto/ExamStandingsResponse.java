package com.backtoback.contest.exam.dto;

import java.util.List;

import com.backtoback.contest.exam.service.ExamResultQueryService;

/** 결과 버전에 묶인 시험 순위 페이지다 (API 명세서 4.2.8). */
public record ExamStandingsResponse(
    String resultStatus,
    String resultRevision,
    List<Item> items,
    String nextCursor,
    boolean hasMore
) {
    public static ExamStandingsResponse from(ExamResultQueryService.Page result) {
        return new ExamStandingsResponse(
            result.resultStatus(),
            result.resultRevision(),
            result.items().stream().map(Item::from).toList(),
            result.nextCursor(),
            result.hasMore()
        );
    }

    public record Item(String memberId, String nickname, String score, Integer rank, boolean pending) {
        static Item from(ExamResultQueryService.StandingItem result) {
            return new Item(result.memberId(), result.nickname(), result.score(), result.rank(), result.pending());
        }
    }
}

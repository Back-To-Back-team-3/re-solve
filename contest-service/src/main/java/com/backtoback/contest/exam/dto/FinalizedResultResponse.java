package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 같은 확정 버전으로 조회하는 결과 스냅샷. 코드·이메일·숨김 케이스를 포함하지 않는다. (API 명세서 §4.7.9)
 */
public record FinalizedResultResponse(
    String context,
    String contextId,
    String resultStatus,
    String resultRevision,
    OffsetDateTime finalizedAt,
    List<Problem> problems,
    List<Item> items,
    String nextCursor,
    boolean hasMore
) {
    /**
     * 고정 문제 목록. 대회 배점은 null이다.
     */
    public record Problem(String problemId, String problemRevisionId, String score) {
    }

    /**
     * 참가자별 확정 결과. 문맥에 해당하지 않는 점수·해결 수·패널티는 null이다.
     */
    public record Item(
        String memberId,
        String participantId,
        boolean isRanked,
        Integer rank,
        String score,
        Integer solvedCount,
        Integer penaltyMinutes,
        List<ProblemResult> problems
    ) {
    }

    /**
     * 미제출 문제도 포함하는 참가자별 문제 결과.
     */
    public record ProblemResult(
        String problemId,
        String verdict,
        String score,
        Integer passedCount,
        Integer totalCount,
        Boolean isSolved,
        Integer wrongCount,
        OffsetDateTime acceptedAt
    ) {
    }
}

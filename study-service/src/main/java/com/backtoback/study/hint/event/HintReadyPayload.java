package com.backtoback.study.hint.event;

import java.time.OffsetDateTime;

/**
 * S8 HintReady v1 페이로드 (event-contract §4.5 S8).
 * 캐시 적중 즉시 READY, 생성 READY·FALLBACK, 전달 직전 BLOCKED.
 */
public record HintReadyPayload(
    String hintRequestId,
    String memberId,
    String problemId,
    String problemRevisionId,
    int level,
    String result,
    boolean isCached,
    OffsetDateTime completedAt
) {
}

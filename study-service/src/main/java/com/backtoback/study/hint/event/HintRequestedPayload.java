package com.backtoback.study.hint.event;

import java.util.List;

/**
 * S9 HintRequested v1 페이로드 (event-contract §4.5 S9).
 * hint_requests REQUESTED INSERT와 같은 트랜잭션에서 Outbox 기록.
 */
public record HintRequestedPayload(
    String hintRequestId,
    String memberId,
    String problemId,
    String problemRevisionId,
    int level,
    String problemTitle,
    String problemBody,
    List<String> problemTags
) {
}

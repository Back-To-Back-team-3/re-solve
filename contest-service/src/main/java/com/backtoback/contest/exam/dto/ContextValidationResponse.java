package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;

/**
 * 문맥 검증 결과. 종료 공개 일반 열람은 참가 ID와 개인 마감이 null이다. (API 명세서 §4.7.1)
 */
public record ContextValidationResponse(
    boolean allowed,
    String participantId,
    String problemRevisionId,
    String resultVisibility,
    OffsetDateTime personalEndsAt,
    OffsetDateTime serverNow
) {
}

package com.backtoback.member.skill.dto;

import static com.backtoback.member.skill.dto.DiagnosisStartResponse.offset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.backtoback.member.skill.domain.DiagnosisAttempt;

/**
 * 진단 이력 항목 (API 명세서 1.5.7). 재응시 결과가 현재 레벨보다 낮아도 점수를 그대로 보여준다.
 */
public record DiagnosisHistoryItem(
    String diagnosisId,
    String tagId,
    String status,
    BigDecimal score,
    String resultLevel,
    String closeReason,
    OffsetDateTime startedAt,
    OffsetDateTime closedAt
) {

    public static DiagnosisHistoryItem from(DiagnosisAttempt attempt) {
        return new DiagnosisHistoryItem(
            String.valueOf(attempt.getId()),
            String.valueOf(attempt.getTagId()),
            attempt.getStatus().name(),
            attempt.getScore(),
            attempt.getResultLevel() == null ? null : attempt.getResultLevel().name(),
            attempt.getCloseReason(),
            offset(attempt.getStartedAt()),
            offset(attempt.getClosedAt())
        );
    }
}

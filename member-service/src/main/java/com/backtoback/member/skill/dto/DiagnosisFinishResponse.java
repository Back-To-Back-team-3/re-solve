package com.backtoback.member.skill.dto;

import static com.backtoback.member.skill.dto.DiagnosisStartResponse.offset;

import java.time.OffsetDateTime;

import com.backtoback.member.skill.domain.DiagnosisAttempt;

/**
 * 진단 최종 제출 응답 (API 명세서 1.5.6).
 */
public record DiagnosisFinishResponse(String diagnosisId, String status, OffsetDateTime closedAt) {

    public static DiagnosisFinishResponse from(DiagnosisAttempt attempt) {
        return new DiagnosisFinishResponse(
            String.valueOf(attempt.getId()),
            attempt.getStatus().name(),
            offset(attempt.getClosedAt())
        );
    }
}

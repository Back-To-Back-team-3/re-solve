package com.backtoback.member.skill.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.skill.service.DiagnosisService.StartedDiagnosis;

/**
 * 진단 시작 응답 (API 명세서 1.5.1).
 */
public record DiagnosisStartResponse(
    String diagnosisId,
    String tagId,
    OffsetDateTime startedAt,
    OffsetDateTime endsAt,
    OffsetDateTime serverNow,
    List<Problem> problems
) {

    public static DiagnosisStartResponse from(StartedDiagnosis started) {
        return new DiagnosisStartResponse(
            String.valueOf(started.attempt().getId()),
            String.valueOf(started.attempt().getTagId()),
            offset(started.attempt().getStartedAt()),
            offset(started.attempt().getEndsAt()),
            offset(started.serverNow()),
            started
                .problems()
                .stream()
                .map(
                    candidate -> new Problem(
                        candidate.problemId(),
                        candidate.problemRevisionId(),
                        candidate.difficulty(),
                        candidate.title()
                    )
                )
                .toList()
        );
    }

    static OffsetDateTime offset(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }

    public record Problem(String problemId, String problemRevisionId, int difficulty, String title) {
    }
}

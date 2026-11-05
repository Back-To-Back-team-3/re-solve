package com.backtoback.contest.exam.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamParticipant;

/** 시험 등록의 현재 참가 상태다 (API 명세서 4.2.1). */
public record ExamParticipationResponse(
    String participantId,
    String examId,
    String status,
    OffsetDateTime enteredAt,
    OffsetDateTime personalEndsAt,
    OffsetDateTime serverNow,
    String participantRevision
) {
    public static ExamParticipationResponse from(ExamParticipant participant, LocalDateTime now) {
        return new ExamParticipationResponse(
            participant.getId().toString(),
            participant.getExam().getId().toString(),
            participant.getStatus().name(),
            offset(participant.getEnteredAt()),
            offset(participant.getPersonalEndsAt()),
            offset(now),
            Long.toString(participant.getParticipantRevision())
        );
    }

    public static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }
}

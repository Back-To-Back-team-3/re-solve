package com.backtoback.contest.exam.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.backtoback.contest.exam.domain.ExamParticipant;

/** 최초 입장 시각과 개인 마감이다 (API 명세서 4.2.2). */
public record ExamEntryResponse(
    String participantId,
    String status,
    OffsetDateTime enteredAt,
    OffsetDateTime personalEndsAt,
    OffsetDateTime serverNow,
    String participantRevision
) {
    public static ExamEntryResponse from(ExamParticipant participant, LocalDateTime now) {
        var response = ExamParticipationResponse.from(participant, now);
        return new ExamEntryResponse(
            response.participantId(),
            response.status(),
            response.enteredAt(),
            response.personalEndsAt(),
            response.serverNow(),
            response.participantRevision()
        );
    }
}

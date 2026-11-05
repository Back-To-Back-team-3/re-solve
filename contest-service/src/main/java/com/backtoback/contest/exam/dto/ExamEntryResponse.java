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
    /**
     * 최초 입장 시각과 개인 마감을 서버 시각과 함께 반환한다.
     *
     * @param participant 입장 참가
     * @param now 판정에 사용한 서버 시각
     * @return 입장 응답
     */
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

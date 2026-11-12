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
    /**
     * 저장된 참가와 판정 시각을 등록 응답으로 변환한다.
     *
     * @param participant 시험 참가
     * @param now 판정에 사용한 서버 시각
     * @return 참가 상태와 개인 마감
     */
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

    /**
     * DB의 서비스 시간대 시각을 응답용 오프셋으로 변환한다.
     *
     * @param value 서비스 시간대의 시각, 없으면 null
     * @return 오프셋이 포함된 시각 또는 null
     */
    public static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }
}

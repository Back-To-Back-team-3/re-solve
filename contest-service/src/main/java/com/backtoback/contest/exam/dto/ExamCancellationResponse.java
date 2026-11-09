package com.backtoback.contest.exam.dto;

import com.backtoback.contest.exam.domain.ExamParticipant;

/** 취소된 참가의 식별자와 상태 버전이다 (API 명세서 4.2.10). */
public record ExamCancellationResponse(String participantId, String status, String participantRevision) {
    /**
     * 저장된 참가의 상태와 버전을 취소 응답으로 변환한다.
     *
     * @param participant 취소된 참가
     * @return 참가 식별자와 상태 버전
     */
    public static ExamCancellationResponse from(ExamParticipant participant) {
        return new ExamCancellationResponse(
            participant.getId().toString(),
            participant.getStatus().name(),
            Long.toString(participant.getParticipantRevision())
        );
    }
}

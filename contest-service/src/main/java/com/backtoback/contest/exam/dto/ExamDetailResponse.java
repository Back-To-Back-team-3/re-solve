package com.backtoback.contest.exam.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipant;

/** 문제 식별자 없이 시험 설정과 본인의 활성 참가를 제공한다 (API 명세서 4.1.3). */
public record ExamDetailResponse(
    String examId,
    String studyId,
    String title,
    String mode,
    String status,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    OffsetDateTime serverNow,
    String totalScore,
    long problemCount,
    String examRevision,
    String version,
    String resultRevision,
    Participation myParticipation
) {
    /** 저장된 시험과 활성 참가를 상세 응답으로 변환한다. */
    public static ExamDetailResponse from(Exam exam, long count, ExamParticipant participant, LocalDateTime now) {
        return new ExamDetailResponse(
            exam.getId().toString(),
            exam.getStudyId().toString(),
            exam.getTitle(),
            exam.getMode().name(),
            exam.getStatus().name(),
            offset(exam.getStartsAt()),
            offset(exam.getEndsAt()),
            offset(now),
            exam.getTotalScore().toPlainString(),
            count,
            Integer.toString(exam.getExamRevision()),
            Long.toString(exam.getVersion()),
            Long.toString(exam.getResultRevision()),
            participant == null ? null : Participation.from(participant)
        );
    }

    /** 서비스 시간대의 저장 시각에 오프셋을 붙인다. */
    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }

    /** 본인의 취소되지 않은 참가 정보다. */
    public record Participation(
        String participantId,
        String status,
        OffsetDateTime enteredAt,
        OffsetDateTime personalEndsAt
    ) {
        /** 참가 상태와 최초 입장 및 개인 마감을 변환한다. */
        private static Participation from(ExamParticipant participant) {
            return new Participation(
                participant.getId().toString(),
                participant.getStatus().name(),
                offset(participant.getEnteredAt()),
                offset(participant.getPersonalEndsAt())
            );
        }
    }
}

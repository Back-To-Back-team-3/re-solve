package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipant;

/** 스터디 시험 목록의 항목이다 (API 명세서 4.1.2). */
public record ExamListResponse(
    String examId,
    String title,
    String status,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    long problemCount,
    String myParticipantStatus
) {
    /** 저장된 시험과 활성 참가를 목록 항목으로 변환한다. */
    public static ExamListResponse from(Exam exam, long problemCount, ExamParticipant participant) {
        return new ExamListResponse(
            exam.getId().toString(),
            exam.getTitle(),
            exam.getStatus().name(),
            exam.getStartsAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            exam.getEndsAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            problemCount,
            participant == null ? null : participant.getStatus().name()
        );
    }
}

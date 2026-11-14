package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.Exam;

/** 취소된 시험의 현재 상태와 버전을 반환한다. */
public record ExamCancelResponse(
    String examId,
    String status,
    OffsetDateTime canceledAt,
    String examRevision,
    String version
) {
    /** 저장된 시험의 취소 상태를 응답으로 변환한다. */
    public static ExamCancelResponse from(Exam exam) {
        return new ExamCancelResponse(
            exam.getId().toString(),
            exam.getStatus().name(),
            exam.getCanceledAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            Integer.toString(exam.getExamRevision()),
            Long.toString(exam.getVersion())
        );
    }
}

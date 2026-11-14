package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;

import com.backtoback.contest.exam.service.ExamSubmissionService;

/** 직접 제출 접수 결과다 (API 명세서 4.2.5). */
public record ExamSubmissionResponse(
    String examSubmissionId,
    String status,
    OffsetDateTime receivedAt,
    String draftSeq,
    OffsetDateTime personalEndsAt
) {
    public static ExamSubmissionResponse from(ExamSubmissionService.Response result) {
        return new ExamSubmissionResponse(
            result.examSubmissionId(),
            result.status(),
            result.receivedAt(),
            result.draftSeq(),
            result.personalEndsAt()
        );
    }
}

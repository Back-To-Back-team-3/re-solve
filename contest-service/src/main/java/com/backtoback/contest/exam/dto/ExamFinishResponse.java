package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.contest.exam.service.ExamAutoSubmissionService;

/** 개인 시험 종료와 자동 접수 결과다 (API 명세서 4.2.6). */
public record ExamFinishResponse(
    String participantId,
    String status,
    OffsetDateTime personalEndsAt,
    OffsetDateTime autoSubmittedAt,
    List<String> autoSubmissionIds
) {
    public static ExamFinishResponse from(ExamAutoSubmissionService.Response result) {
        return new ExamFinishResponse(
            result.participantId(),
            result.status(),
            result.personalEndsAt(),
            result.autoSubmittedAt(),
            result.autoSubmissionIds()
        );
    }
}

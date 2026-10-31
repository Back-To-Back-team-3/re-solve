package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;

/** 원본 코드·테스트를 포함하지 않는 본인 제출 요약. */
public record SubmissionListResponse(
    String submissionId,
    String problemId,
    String language,
    String context,
    String status,
    JudgeVerdict verdict,
    Integer maxTimeMs,
    Integer maxMemoryKb,
    OffsetDateTime submittedAt
) {
    public static SubmissionListResponse from(Submission submission) {
        return new SubmissionListResponse(
            submission.getId().toString(),
            Long.toString(submission.getProblemId()),
            submission.getLanguage(),
            submission.getContext().name(),
            submission.getStatus().name(),
            submission.getVerdict(),
            submission.getMaxTimeMs(),
            submission.getMaxMemoryKb(),
            submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}

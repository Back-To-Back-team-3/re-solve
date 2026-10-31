package com.backtoback.judge.submission.dto;

import com.backtoback.judge.submission.domain.Submission;

/** 본인 일반 제출의 원본 코드. */
public record SubmissionSourceResponse(String language, String sourceCode) {
    public static SubmissionSourceResponse from(Submission submission) {
        return new SubmissionSourceResponse(submission.getLanguage(), submission.getSourceCode());
    }
}

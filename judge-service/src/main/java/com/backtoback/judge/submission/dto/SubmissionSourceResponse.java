package com.backtoback.judge.submission.dto;

import com.backtoback.judge.submission.domain.Submission;

/** 본인 일반 제출의 원본 코드. */
public record SubmissionSourceResponse(String language, String sourceCode) {
    /**
     * 소유권 검증을 마친 제출에서 공개할 원본 코드 응답을 구성한다.
     *
     * @param submission 조회 권한을 확인한 일반 제출
     * @return 언어와 원본 코드 응답
     */
    public static SubmissionSourceResponse from(Submission submission) {
        return new SubmissionSourceResponse(submission.getLanguage(), submission.getSourceCode());
    }
}

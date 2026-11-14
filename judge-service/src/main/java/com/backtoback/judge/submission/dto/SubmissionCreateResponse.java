package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.submission.domain.Submission;

/**
 * 신규 접수와 동일 멱등 재요청의 원래 202 응답이다. (API 명세서 §3.1.2)
 *
 * @param submissionId 제출 ID
 * @param judgeAttempt 최초 채점 회차
 * @param status 최초 접수 상태
 * @param submittedAt 원래 서버 접수 시각
 * @param statusUrl 결과 조회 경로
 */
public record SubmissionCreateResponse(
    String submissionId,
    int judgeAttempt,
    String status,
    OffsetDateTime submittedAt,
    String statusUrl
) {
    /**
     * 현재 채점 상태와 무관하게 원래 접수 응답을 복원한다.
     *
     * @param submission 저장된 제출
     * @return 최초 접수 응답
     */
    public static SubmissionCreateResponse from(Submission submission) {
        return new SubmissionCreateResponse(
            Long.toString(submission.getId()),
            1,
            "QUEUED",
            submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            "/api/v1/submissions/" + submission.getId()
        );
    }
}

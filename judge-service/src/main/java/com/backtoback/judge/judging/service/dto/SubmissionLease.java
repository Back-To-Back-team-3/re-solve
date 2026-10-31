package com.backtoback.judge.judging.service.dto;

import java.time.LocalDateTime;

/** 채점 회차와 실행 시도를 함께 식별하는 선점 소유권. */
public record SubmissionLease(
    long jobId,
    long submissionId,
    int judgeAttempt,
    int tryCount,
    String token,
    LocalDateTime deadlineAt
) {
}

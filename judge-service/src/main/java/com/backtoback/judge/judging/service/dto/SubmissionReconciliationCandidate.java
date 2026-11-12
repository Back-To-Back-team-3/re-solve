package com.backtoback.judge.judging.service.dto;

/** 잠금 없이 선정한 제출·최신 작업의 상태 대사 후보. */
public record SubmissionReconciliationCandidate(
    long submissionId,
    Long jobId,
    int judgeAttempt,
    String observedStatus,
    String jobStatus,
    long problemId,
    long revisionId
) {
}

package com.backtoback.contest.exam.dto;

/**
 * 본인 AC·부모 FINALIZED 조건을 만족하는 코드 스냅샷. (API 명세서 §4.7.8)
 */
public record ShareableSubmissionResponse(
    String context,
    String contextId,
    String receiptId,
    String submissionId,
    String memberId,
    String problemId,
    String problemRevisionId,
    String language,
    String sourceCode,
    String verdict,
    String resultRevision
) {
}

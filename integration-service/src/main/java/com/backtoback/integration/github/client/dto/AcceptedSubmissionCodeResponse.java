package com.backtoback.integration.github.client.dto;

import java.time.OffsetDateTime;

/**
 * judge-service GitHub 동기화용 정답 코드 조회 응답 DTO (api-spec.md &gt; 3.3.3).
 */
public record AcceptedSubmissionCodeResponse(
    String submissionId,
    String problemId,
    String language,
    String sourceCode,
    Integer judgeAttempt,
    OffsetDateTime submittedAt
) {
}

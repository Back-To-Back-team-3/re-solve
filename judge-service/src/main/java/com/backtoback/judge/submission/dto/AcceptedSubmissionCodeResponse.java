package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.submission.domain.Submission;

/** GitHub 동기화용 일반 풀이 정답 코드 조회 응답. (API 명세서 §3.3.3) */
public record AcceptedSubmissionCodeResponse(
    String submissionId,
    String problemId,
    String language,
    String sourceCode,
    Integer judgeAttempt,
    OffsetDateTime submittedAt
) {
    /**
     * 소유자와 일반 풀이 AC 판정을 확인한 제출에서 GitHub 동기화용 코드 응답을 구성한다.
     *
     * @param submission 조회 조건을 통과한 일반 풀이 정답 제출
     * @return 제출 식별자, 언어, 원본 코드, 회차 및 서비스 시간대 기준 제출 시각
     */
    public static AcceptedSubmissionCodeResponse from(Submission submission) {
        return new AcceptedSubmissionCodeResponse(
            submission.getId().toString(),
            Long.toString(submission.getProblemId()),
            submission.getLanguage(),
            submission.getSourceCode(),
            submission.getJudgeAttempt(),
            submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}

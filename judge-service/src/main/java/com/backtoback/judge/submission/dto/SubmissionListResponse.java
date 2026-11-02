package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.service.SubmissionSummary;

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
    /**
     * 제출 요약을 공개 응답으로 변환하고 접수 시각에 서비스 시간대를 적용한다.
     *
     * @param submission 목록 조회의 스칼라 결과
     * @return 공개할 제출 요약
     */
    public static SubmissionListResponse from(SubmissionSummary submission) {
        return new SubmissionListResponse(
            submission.id().toString(),
            Long.toString(submission.problemId()),
            submission.language(),
            submission.context().name(),
            submission.status().name(),
            submission.verdict(),
            submission.maxTimeMs(),
            submission.maxMemoryKb(),
            submission.submittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}

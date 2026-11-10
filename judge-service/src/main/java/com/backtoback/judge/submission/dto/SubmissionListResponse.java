package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.service.SubmissionSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 원본 코드·테스트를 포함하지 않는 본인 제출 요약. */
public record SubmissionListResponse(
    String submissionId,
    String problemId,
    String language,
    String context,
    @JsonInclude(JsonInclude.Include.ALWAYS) String contextId,
    String status,
    @JsonInclude(JsonInclude.Include.ALWAYS) JudgeVerdict verdict,
    @JsonInclude(JsonInclude.Include.ALWAYS) Integer maxTimeMs,
    @JsonInclude(JsonInclude.Include.ALWAYS) Integer maxMemoryKb,
    OffsetDateTime submittedAt
) {
    /**
     * 제출 요약을 공개 응답으로 변환하고 시험·진단의 판정과 실행 지표를 가린다.
     *
     * @param submission 목록 조회의 스칼라 결과
     * @return 공개할 제출 요약
     */
    public static SubmissionListResponse from(SubmissionSummary submission) {
        boolean practice = submission.context() == Context.PRACTICE;
        return new SubmissionListResponse(
            submission.id().toString(),
            Long.toString(submission.problemId()),
            submission.language(),
            submission.context().name(),
            practice ? null : submission.contextId().toString(),
            submission.status().name(),
            practice ? submission.verdict() : null,
            practice ? submission.maxTimeMs() : null,
            practice ? submission.maxMemoryKb() : null,
            submission.submittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}

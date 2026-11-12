package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 시험·대회 제출의 현재 회차 결과. (API 명세서 §3.3.1) */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InternalSubmissionResultResponse(
    String submissionId,
    Submission.Context context,
    String contextId,
    String sourceSubmissionId,
    Submission.Status status,
    int judgeAttempt,
    JudgeVerdict verdict,
    Integer passedCount,
    Integer totalCount,
    Integer maxTimeMs,
    Integer maxMemoryKb,
    @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime submittedAt
) {
    /**
     * 시험·대회 제출을 현재 상태와 현재 완료 회차의 판정으로 변환한다.
     *
     * @param submission 문맥을 확인한 제출
     * @return 코드와 테스트 자료를 제외한 결과
     */
    public static InternalSubmissionResultResponse from(Submission submission) {
        boolean completed = submission.getStatus() == Submission.Status.COMPLETED;
        return new InternalSubmissionResultResponse(
            submission.getId().toString(),
            submission.getContext(),
            submission.getContextId().toString(),
            submission.getSourceSubmissionId().toString(),
            submission.getStatus(),
            submission.getJudgeAttempt(),
            completed ? submission.getVerdict() : null,
            completed ? submission.getPassedCount() : null,
            completed ? submission.getTotalCount() : null,
            completed ? submission.getMaxTimeMs() : null,
            completed ? submission.getMaxMemoryKb() : null,
            submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}

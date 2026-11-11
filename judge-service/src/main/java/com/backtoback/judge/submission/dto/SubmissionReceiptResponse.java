package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** 원래 접수의 채점 연결과 상태. (API 명세서 §3.3.6) */
public record SubmissionReceiptResponse(boolean found, @JsonUnwrapped Details details) {
    /**
     * 접수를 발견하면 대사에 필요한 원본 식별자와 현재 결과를 반환한다.
     *
     * @param submission 원래 접수에 연결된 시험·대회 제출
     * @return 미접수일 때 found만 포함하는 대사 결과
     */
    public static SubmissionReceiptResponse from(Optional<Submission> submission) {
        return submission.map(value -> {
            var result = InternalSubmissionResultResponse.from(value);
            return new SubmissionReceiptResponse(
                true,
                new Details(
                    result.submissionId(),
                    result.context(),
                    result.contextId(),
                    result.sourceSubmissionId(),
                    Long.toString(value.getMemberId()),
                    Long.toString(value.getProblemId()),
                    Long.toString(value.getProblemRevisionId()),
                    result.submittedAt(),
                    result.status(),
                    result.judgeAttempt(),
                    result.verdict(),
                    result.passedCount(),
                    result.totalCount()
                )
            );
        }).orElseGet(() -> new SubmissionReceiptResponse(false, null));
    }

    /** 원본 접수에 연결된 제출이 있을 때만 포함하는 필드. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Details(
        String submissionId,
        Submission.Context context,
        String contextId,
        String receiptId,
        String userId,
        String problemId,
        String problemRevisionId,
        @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime submittedAt,
        Submission.Status status,
        int judgeAttempt,
        JudgeVerdict verdict,
        Integer passedCount,
        Integer totalCount
    ) {
    }
}

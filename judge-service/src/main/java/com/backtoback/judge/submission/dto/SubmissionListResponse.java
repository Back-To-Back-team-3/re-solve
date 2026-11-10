package com.backtoback.judge.submission.dto;

import java.time.format.DateTimeFormatter;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.service.SubmissionSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(format = "date-time") String submittedAt
) {
    private static final DateTimeFormatter EXAM_RECEIPT_TIME
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    /**
     * 제출 요약을 공개 응답으로 변환하고 시험·진단의 판정과 실행 지표를 가린다.
     * 시험 접수 시각은 API 공통 규약에 따라 마이크로초 6자리로 반환한다. (API 명세서 §0.1)
     *
     * @param submission 목록 조회의 스칼라 결과
     * @return 공개할 제출 요약
     */
    public static SubmissionListResponse from(SubmissionSummary submission) {
        boolean practice = submission.context() == Context.PRACTICE;
        var submittedAt = submission.submittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
        var timeFormat
            = submission.context() == Context.EXAM ? EXAM_RECEIPT_TIME : DateTimeFormatter.ISO_OFFSET_DATE_TIME;
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
            timeFormat.format(submittedAt)
        );
    }
}

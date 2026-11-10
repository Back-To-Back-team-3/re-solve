package com.backtoback.common.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 시험 제출의 채점 결과 이벤트 v1 업무 필드. (이벤트 계약 §4.3 J2) */
public record ExamSubmissionJudgedPayload(
    String context,
    String examId,
    String examSubmissionId,
    String submissionId,
    String userId,
    String problemId,
    String problemRevisionId,
    int judgeAttempt,
    String status,
    String verdict,
    int passedCount,
    int totalCount,
    @JsonInclude(JsonInclude.Include.ALWAYS) Integer maxTimeMs,
    @JsonInclude(JsonInclude.Include.ALWAYS) Integer maxMemoryKb,
    String submittedAt
) {
}

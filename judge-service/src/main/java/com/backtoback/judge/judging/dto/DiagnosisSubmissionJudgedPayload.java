package com.backtoback.judge.judging.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 진단 제출의 채점 결과 이벤트 v1 업무 필드. (이벤트 계약 §4.3 J5) */
public record DiagnosisSubmissionJudgedPayload(
    String context,
    String diagnosisId,
    String diagnosisSubmissionId,
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

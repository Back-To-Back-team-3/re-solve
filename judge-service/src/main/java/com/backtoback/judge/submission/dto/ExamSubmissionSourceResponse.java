package com.backtoback.judge.submission.dto;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 소유자와 시험 ID를 확인한 제출의 복기용 코드. (API 명세서 §3.3.2) */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ExamSubmissionSourceResponse(
    String submissionId,
    String problemId,
    String problemRevisionId,
    String language,
    String sourceCode,
    JudgeVerdict verdict
) {
    /**
     * 소유자와 시험 ID를 확인한 제출에서 복기용 원본 코드를 구성한다.
     *
     * @param submission 조회 조건을 통과한 시험 제출
     * @return 고정 문제 회차, 언어, 원본 코드와 현재 완료 회차의 판정
     */
    public static ExamSubmissionSourceResponse from(Submission submission) {
        return new ExamSubmissionSourceResponse(
            submission.getId().toString(),
            Long.toString(submission.getProblemId()),
            Long.toString(submission.getProblemRevisionId()),
            submission.getLanguage(),
            submission.getSourceCode(),
            submission.getStatus() == Submission.Status.COMPLETED ? submission.getVerdict() : null
        );
    }
}

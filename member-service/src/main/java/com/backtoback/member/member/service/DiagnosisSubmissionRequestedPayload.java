package com.backtoback.member.member.service;

/**
 * {@code DiagnosisSubmissionRequested} v1 업무 필드 (이벤트 계약 M6). 공통 헤더는 {@code OutboxEventWriter}가 붙인다.
 *
 * @param diagnosisSubmissionId judge 멱등키 ({@code source_submission_id})
 * @param submissionType {@code MANUAL} / {@code AUTO}
 * @param receivedAt {@code <= ends_at}. judge {@code submitted_at}
 */
public record DiagnosisSubmissionRequestedPayload(
    String diagnosisId,
    String diagnosisSubmissionId,
    String userId,
    String problemId,
    String problemRevisionId,
    String language,
    String sourceCode,
    String draftSeq,
    String submissionType,
    String receivedAt
) {

}

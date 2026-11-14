package com.backtoback.member.skill.dto;

import static com.backtoback.member.skill.dto.DiagnosisStartResponse.offset;

import java.time.OffsetDateTime;

import com.backtoback.member.skill.store.DiagnosisSubmissionStore.Submission;

/**
 * 진단 문항 제출 응답 (API 명세서 1.5.5).
 */
public record DiagnosisSubmitResponse(String diagnosisSubmissionId, String status, OffsetDateTime receivedAt) {

    public static DiagnosisSubmitResponse from(Submission submission) {
        return new DiagnosisSubmitResponse(
            String.valueOf(submission.id()),
            submission.status(),
            offset(submission.receivedAt())
        );
    }
}

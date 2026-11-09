package com.backtoback.contest.admin.dto;

/** 재확정된 시험의 새 불변 결과 버전이다. */
public record ExamRefinalizationResponse(String examId, String status, String resultRevision, String finalizedAt) {
}

package com.backtoback.contest.exam.dto;

/**
 * 부모 시험의 재채점 가능 여부. 조회는 작업 접수나 결과 확정 방지를 수행하지 않는다. (API 명세서 §4.7.10)
 */
public record ExamRejudgeEligibilityResponse(String examId, String status, boolean eligible) {
}

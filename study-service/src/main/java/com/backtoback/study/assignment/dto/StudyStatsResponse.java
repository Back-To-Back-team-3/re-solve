package com.backtoback.study.assignment.dto;

/**
 * 문제집 통계 (API 명세서 §5.4.3 studyStats). ACTIVE 문제가 없거나 구성원이 없으면 null.
 */
public record StudyStatsResponse(Double avgCompletionRate, Double avgOnTimeRate) {

}

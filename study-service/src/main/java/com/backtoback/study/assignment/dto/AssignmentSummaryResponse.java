package com.backtoback.study.assignment.dto;

import com.backtoback.study.assignment.domain.AssignmentStatus;

/**
 * 문제집 목록 항목 응답 (API 명세서 §5.4.2).
 */
public record AssignmentSummaryResponse(
    String id,
    String title,
    AssignmentStatus status,
    String startsAt,
    String deadlineAt,
    int activeProblemCount,
    MyStatusResponse myStatus
) {

}

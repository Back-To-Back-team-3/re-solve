package com.backtoback.study.assignment.dto;

import java.time.LocalDateTime;

import com.backtoback.study.assignment.domain.AssignmentStatus;

/**
 * 문제집 목록 항목 응답 (API 명세서 §5.4.2).
 */
public record AssignmentSummaryResponse(
    Long id,
    String title,
    AssignmentStatus status,
    LocalDateTime startsAt,
    LocalDateTime deadlineAt,
    int activeProblemCount,
    MyStatusResponse myStatus
) {

}

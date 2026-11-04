package com.backtoback.study.assignment.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 문제집 상세·진행률 응답 (API 명세서 §5.4.3).
 */
public record AssignmentDetailResponse(
    Long assignmentId,
    String title,
    String goal,
    Long tagId,
    AssignmentStatus status,
    @JsonProperty("isPreSolvedAllowed") boolean preSolvedAllowed,
    LocalDateTime startsAt,
    LocalDateTime deadlineAt,
    List<ProblemSnapshotResponse> problems,
    List<MemberProgressResponse> memberProgress,
    StudyStatsResponse studyStats
) {

}

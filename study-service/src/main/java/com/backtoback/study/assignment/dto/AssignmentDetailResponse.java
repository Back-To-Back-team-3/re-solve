package com.backtoback.study.assignment.dto;

import java.util.List;

import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 문제집 상세·진행률 응답 (API 명세서 §5.4.3).
 */
public record AssignmentDetailResponse(
    String assignmentId,
    String title,
    String goal,
    String tagId,
    AssignmentStatus status,
    @JsonProperty("isPreSolvedAllowed") boolean preSolvedAllowed,
    String startsAt,
    String deadlineAt,
    List<ProblemSnapshotResponse> problems,
    List<MemberProgressResponse> memberProgress,
    StudyStatsResponse studyStats
) {

}

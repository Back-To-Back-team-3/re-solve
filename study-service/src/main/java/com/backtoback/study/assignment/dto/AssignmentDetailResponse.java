package com.backtoback.study.assignment.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.backtoback.study.assignment.domain.Assignment;
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

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    public static AssignmentDetailResponse from(
        Assignment assignment,
        List<ProblemSnapshotResponse> problems,
        List<MemberProgressResponse> memberProgress,
        StudyStatsResponse studyStats
    ) {
        return new AssignmentDetailResponse(
            String.valueOf(assignment.getId()),
            assignment.getTitle(),
            assignment.getGoal(),
            assignment.getTagId() != null ? String.valueOf(assignment.getTagId()) : null,
            assignment.getStatus(),
            assignment.isPreSolvedAllowed(),
            toOffsetString(assignment.getStartsAt()),
            toOffsetString(assignment.getDeadlineAt()),
            problems,
            memberProgress,
            studyStats
        );
    }

    private static String toOffsetString(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.of("+09:00")).format(FORMATTER);
    }
}

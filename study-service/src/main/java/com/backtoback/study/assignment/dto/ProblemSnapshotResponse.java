package com.backtoback.study.assignment.dto;

import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.CompletionType;
import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * 문제집 배정 문제 스냅샷 (API 명세서 §5.4.3 problems[]).
 * revisionNumber는 ERD §4.5.6에 저장되지 않으므로 null을 반환한다.
 */
public record ProblemSnapshotResponse(
    String problemId,
    String assignedRevisionId,
    Integer revisionNumber,
    String title,
    int difficulty,
    @JsonRawValue String tags,
    AssignmentProblemStatus status,
    CompletionType myCompletionType
) {

    public static ProblemSnapshotResponse from(AssignmentProblem problem, CompletionType myCompletionType) {
        return new ProblemSnapshotResponse(
            String.valueOf(problem.getProblemId()),
            String.valueOf(problem.getAssignedRevisionId()),
            null,
            problem.getProblemTitle(),
            problem.getProblemDifficulty(),
            problem.getProblemTagsJson(),
            problem.getStatus(),
            myCompletionType
        );
    }
}

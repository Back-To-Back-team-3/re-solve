package com.backtoback.study.assignment.dto;

import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.CompletionType;
import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * 문제집 배정 문제 스냅샷 (API 명세서 §5.4.3 problems[]). revisionNumber는 컬럼 추가 이전 행이면 null이다.
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
            problem.getRevisionNumber(),
            problem.getProblemTitle(),
            problem.getProblemDifficulty(),
            problem.getProblemTagsJson(),
            problem.getStatus(),
            myCompletionType
        );
    }
}

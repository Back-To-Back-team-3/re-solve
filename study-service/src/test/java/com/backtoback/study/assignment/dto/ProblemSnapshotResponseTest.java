package com.backtoback.study.assignment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.CompletionType;

class ProblemSnapshotResponseTest {

    @Test
    @DisplayName("revisionNumber가 있는 배정 문제는 실제 회차 번호를 반환한다")
    void from_withRevisionNumber_returnsActualValue() {
        AssignmentProblem problem = AssignmentProblem.assign(1L, 101L, 201L, 3, "최단 경로", 3, "[]", 1);

        ProblemSnapshotResponse response = ProblemSnapshotResponse.from(problem, CompletionType.ON_TIME);

        assertThat(response.revisionNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("revisionNumber가 없는 배정 문제(컬럼 추가 이전 행)는 null을 반환한다")
    void from_withoutRevisionNumber_returnsNull() {
        AssignmentProblem problem = AssignmentProblem.assign(1L, 101L, 201L, null, "최단 경로", 3, "[]", 1);

        ProblemSnapshotResponse response = ProblemSnapshotResponse.from(problem, null);

        assertThat(response.revisionNumber()).isNull();
    }
}

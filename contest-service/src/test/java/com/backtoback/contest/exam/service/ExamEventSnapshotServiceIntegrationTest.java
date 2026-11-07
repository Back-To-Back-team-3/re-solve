package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 시험 상태에 따른 SSE 집계의 공개 범위를 검증한다. */
class ExamEventSnapshotServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("전체 종료 전 SSE에는 본인 제출 진행률을 공개하지 않는다")
    void hidesEventProgressBeforeExamClose() {
        // given
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        // when
        var hidden = eventSnapshots.read(examId, "7");
        // then
        assertThat(hidden.terminalCount()).isNull();
        assertThat(hidden.totalSubmissionCount()).isNull();
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        var visible = eventSnapshots.read(examId, "7");
        assertThat(visible.terminalCount()).isZero();
        assertThat(visible.totalSubmissionCount()).isZero();
    }

}

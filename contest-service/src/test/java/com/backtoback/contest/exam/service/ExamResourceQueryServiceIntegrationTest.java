package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.backtoback.common.time.ClockConfiguration;

/** 실제 시험 상태와 내부 복구 조회의 경계를 검증한다. */
class ExamResourceQueryServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Autowired
    private ExamResourceQueryService queries;

    @Test
    @DisplayName("개인 종료 후에도 실제 입장한 미확정 시험은 탈퇴를 차단한다")
    void activeUntilFinalized() {
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        assertThat(queries.active("7").withdrawalBlocked()).isTrue();
        jdbc.update("UPDATE exams SET status='FINALIZED' WHERE id=?", examId);
        assertThat(queries.active("7").withdrawalBlocked()).isFalse();
    }

    @Test
    @DisplayName("진행 시험의 전체 시간이 지나면 문제 사용은 비공개를 막지 않는다")
    void problemUsageUsesServerTime() {
        assertThat(queries.usage("101").hasBlockingUsage()).isTrue();
        jdbc
            .update(
                "UPDATE exams SET ends_at=? WHERE id=?",
                LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusSeconds(1),
                examId
            );
        assertThat(queries.usage("101").hasBlockingUsage()).isFalse();
    }

    @Test
    @DisplayName("개인 종료는 힌트 차단에 포함하고 시험 전체 종료는 제외한다")
    void hintBlockEndsWithExam() {
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        var blocks = queries.hintBlocks("7", null, 20);
        assertThat(blocks.items()).hasSize(1);
        assertThat(blocks.items().getFirst().problemIds()).containsExactly("101", "102");
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        assertThat(queries.hintBlocks("7", null, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("힌트 차단 커서는 같은 회원의 다음 참가부터 조회한다")
    void hintBlockCursorBelongsToMember() {
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                    + "total_score,result_revision,version,created_at,updated_at) "
                    + "SELECT study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,total_score,"
                    + "result_revision,version,created_at,updated_at " + "FROM exams WHERE id=?",
                examId
            );
        long secondExam = jdbc.queryForObject("SELECT MAX(id) FROM exams", Long.class);
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                    + "entered_at,personal_ends_at,total_score,is_ranked,version,created_at,updated_at) "
                    + "SELECT ?,member_id,status,participant_revision,registered_at,entered_at,personal_ends_at,"
                    + "total_score,is_ranked,version,created_at,updated_at FROM exam_participants WHERE id=?",
                secondExam,
                participantId
            );

        var first = queries.hintBlocks("7", null, 1);
        assertThat(first.hasMore()).isTrue();
        var second = queries.hintBlocks("7", first.nextCursor(), 1);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().contextId()).isEqualTo(Long.toString(secondExam));
        assertThat(second.items().getFirst().participantId()).isNotEqualTo(Long.toString(participantId));
        assertThatThrownBy(() -> queries.hintBlocks("8", first.nextCursor(), 1))
            .isInstanceOf(com.backtoback.contest.global.error.ContestException.class);
    }

    @Test
    @DisplayName("시험이 확정되면 재채점 가능 여부가 false로 바뀐다")
    void eligibilityUsesParentStatus() {
        assertThat(queries.eligibility(examId).eligible()).isTrue();
        jdbc.update("UPDATE exams SET status='FINALIZED' WHERE id=?", examId);
        assertThat(queries.eligibility(examId).eligible()).isFalse();
    }
}

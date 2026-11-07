package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 본인 결과와 순위의 공개 조건·버전 커서를 검증한다. */
class ExamResultQueryServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("시험 결과는 종료 전 비공개이며 확정 후 스냅샷을 조회한다")
    void resultsFollowVisibilityAndSnapshot() {
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        assertThat(results.me(examId, "7").resultStatus()).isEqualTo("HIDDEN");
        assertThat(results.me(examId, "7").problems()).isEmpty();
        assertThatThrownBy(() -> results.standings(examId, "7", null, 20))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_RESULT_NOT_AVAILABLE)
            );
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        var provisional = results.me(examId, "7");
        assertThat(provisional.resultStatus()).isEqualTo("PROVISIONAL");
        assertThat(provisional.problems().getFirst().pending()).isTrue();
        jdbc.update("UPDATE exam_submissions SET status='FAILED',is_zero_confirmed=true WHERE exam_id=?", examId);
        jdbc
            .update(
                "UPDATE exam_participants SET status='FINISHED',auto_submitted_at=NOW(6) WHERE id=?",
                participantId
            );
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        var fixed = results.me(examId, "7");
        assertThat(fixed.resultStatus()).isEqualTo("FINALIZED");
        assertThat(fixed.score()).isEqualTo("0.00");
        assertThat(fixed.rank()).isEqualTo(1);
        assertThat(fixed.problems().getFirst().pending()).isFalse();
        jdbc.update("UPDATE exam_participants SET total_score=99 WHERE id=?", participantId);
        assertThat(results.me(examId, "7").score()).isEqualTo("0.00");
        assertThat(results.standings(examId, "7", null, 20).items().getFirst().score()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("시험 순위는 조회 권한과 결과 버전 커서를 확인한다")
    void standingsRequireAuthorizationAndCurrentCursor() {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        jdbc
            .update(
                "UPDATE exam_participants SET is_ranked=true,last_valid_submitted_at=? WHERE id=?",
                now,
                participantId
            );
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                    + "personal_ends_at,total_score,is_ranked,last_valid_submitted_at,version,created_at,updated_at) "
                    + "VALUES (?,8,'FINISHED',1,?,?,0,true,?,0,?,?)",
                examId,
                now,
                now,
                now,
                now,
                now
            );
        assertThatThrownBy(() -> results.standings(examId, "9", null, 1)).isInstanceOf(ContestException.class);
        var page = results.standings(examId, "7", null, 1);
        assertThat(page.hasMore()).isTrue();
        assertThat(results.standings(examId, "7", page.nextCursor(), 1).items()).hasSize(1);
        jdbc.update("UPDATE exams SET result_revision=result_revision+1 WHERE id=?", examId);
        assertThatThrownBy(() -> results.standings(examId, "7", page.nextCursor(), 1))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.RESULT_CURSOR_EXPIRED)
            );
    }

}

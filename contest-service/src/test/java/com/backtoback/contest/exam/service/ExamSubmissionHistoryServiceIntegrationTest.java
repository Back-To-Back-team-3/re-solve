package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
class ExamSubmissionHistoryServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("제출 이력은 종료 전 판정을 숨기고 결과 변경 시 커서를 거절한다")
    void submissionHistoryHidesResultsAndExpiresCursor() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var first = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "첫 코드", "1"));
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            Long.parseLong(first.examSubmissionId()),
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "JUDGED",
                            "AC",
                            3,
                            3,
                            first.receivedAt()
                        )
                    )
            );
        submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "두 번째 코드", "2"));
        // when
        var page = submissionHistory.read(examId, "7", null, null, 1);
        var secondPage = submissionHistory.read(examId, "7", null, page.nextCursor(), 1);
        // then
        assertThat(secondPage.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("REQUESTED");
            assertThat(item.verdict()).isNull();
            assertThat(item.score()).isNull();
        });
        jdbc.update("UPDATE exams SET result_revision=result_revision+1 WHERE id=?", examId);
        assertThatThrownBy(() -> submissionHistory.read(examId, "7", null, page.nextCursor(), 1))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.RESULT_CURSOR_EXPIRED)
            );
    }

}

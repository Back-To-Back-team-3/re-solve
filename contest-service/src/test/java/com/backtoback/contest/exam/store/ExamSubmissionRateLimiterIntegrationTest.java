package com.backtoback.contest.exam.store;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.global.error.ContestException;

/** 롤백된 제출의 예약만 Redis에서 해제한다. */
class ExamSubmissionRateLimiterIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("접수 롤백 후에는 5초를 기다리지 않고 같은 문제를 다시 예약한다")
    void rollbackReleasesReservation() {
        var actual = new ExamSubmissionRateLimiter(redis);
        long member = 800001;
        redis
            .delete(
                java.util.List
                    .of("contest:submission:{" + member + "}:minute", "contest:submission:{" + member + "}:problem:101")
            );
        transactions.executeWithoutResult(status -> {
            actual.acquire(member, 101);
            status.setRollbackOnly();
        });

        assertThatCode(() -> transactions.executeWithoutResult(status -> actual.acquire(member, 101)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> actual.acquire(member, 101)))
            .isInstanceOf(ContestException.class);
    }
}

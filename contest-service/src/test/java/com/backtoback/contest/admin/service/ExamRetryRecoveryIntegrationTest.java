package com.backtoback.contest.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.exam.client.ExamRetryClient;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.exam.service.ExamJudgementService;

/** 원격 결과 불명과 같은 작업의 복구를 검증한다. */
class ExamRetryRecoveryIntegrationTest extends ExamIntegrationTestSupport {
    @Autowired
    private ExamRetryService retry;
    @Autowired
    private ExamRetryStore retryStore;
    @MockitoBean
    private ExamRetryClient client;

    @Test
    @DisplayName("응답 유실은 PENDING을 유지하고 같은 작업으로 복구한다")
    void lostResponseIsRecovered() {
        var submitted = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        long receipt = Long.parseLong(submitted.examSubmissionId());
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            receipt,
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "FAILED",
                            null,
                            null,
                            null,
                            submitted.receivedAt()
                        )
                    )
            );
        given(client.retry(eq(9001L), any()))
            .willThrow(new ResourceAccessException("timeout"))
            .willReturn(new ExamRetryClient.Result("9001", 2, "QUEUED"));
        var request = new ExamRetryRequest("recover-op", 1, "재처리");
        var admin = new GatewayUser("7", UserRole.ADMIN);

        var pending = retry.request(examId, Long.toString(receipt), admin, request);
        assertThat(pending.state()).isEqualTo("PENDING");
        assertThat(pending.targetJudgeAttempt()).isNull();
        assertThat(retryStore.require("recover-op").state()).isEqualTo("PENDING");
        var recovered = retry.request(examId, Long.toString(receipt), admin, request);

        assertThat(recovered.state()).isEqualTo("SUCCEEDED");
        assertThat(recovered.judgeAttempt()).isEqualTo(1);
        assertThat(recovered.targetJudgeAttempt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM exam_submissions WHERE id=?", String.class, receipt))
            .isEqualTo("REQUESTED");
        verify(client, times(2)).retry(eq(9001L), any());
    }

    @Test
    @DisplayName("본문이 없는 409 응답은 미실행 확정 근거로 삼지 않는다")
    void malformedConflictRemainsPending() {
        var submitted = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        long receipt = Long.parseLong(submitted.examSubmissionId());
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            receipt,
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "FAILED",
                            null,
                            null,
                            null,
                            submitted.receivedAt()
                        )
                    )
            );
        given(client.retry(eq(9001L), any())).willThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        var response
            = retry
                .request(
                    examId,
                    Long.toString(receipt),
                    new GatewayUser("7", UserRole.ADMIN),
                    new ExamRetryRequest("unknown-conflict", 1, "재처리")
                );

        assertThat(response.state()).isEqualTo("PENDING");
        assertThat(retryStore.require("unknown-conflict").state()).isEqualTo("PENDING");
    }
}

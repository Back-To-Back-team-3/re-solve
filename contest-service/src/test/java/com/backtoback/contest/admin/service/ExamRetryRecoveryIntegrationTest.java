package com.backtoback.contest.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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

    /** HTTP 상태와 오류 코드가 모두 확정 거절을 나타낼 때만 작업을 종결한다. */
    @ParameterizedTest
    @CsvSource(
        {
            "409,,PENDING",
            "422,OTHER_ERROR,PENDING",
            "422,IDEMPOTENCY_KEY_CONFLICT,FAILED"
        }
    )
    @DisplayName("확정된 멱등 충돌은 실패로 종결하고 불명확한 응답은 보류한다")
    void reconcilesOnlyConfirmedRejections(int httpStatus, String code, String expected) {
        // given
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
        String body = code == null ? "" : "{\"success\":false,\"error\":{\"code\":\"%s\"}}".formatted(code);
        given(client.retry(eq(9001L), any()))
            .willThrow(
                HttpClientErrorException
                    .create(
                        HttpStatus.valueOf(httpStatus),
                        "",
                        HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                    )
            );

        // when
        var response
            = retry
                .request(
                    examId,
                    Long.toString(receipt),
                    new GatewayUser("7", UserRole.ADMIN),
                    new ExamRetryRequest("unknown-conflict", 1, "재처리")
                );

        // then
        assertThat(response.state()).isEqualTo(expected);
        var stored = retryStore.require("unknown-conflict");
        assertThat(stored.state()).isEqualTo(expected);
        assertThat(stored.lastError()).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT judge_attempt FROM exam_submissions WHERE id=?", Integer.class, receipt))
            .isEqualTo(1);
    }
}

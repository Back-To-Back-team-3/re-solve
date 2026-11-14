package com.backtoback.contest.exam.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 시험 실패 재처리 내부 요청과 응답 연결을 확인한다. */
class ExamRetryClientTest {
    @Test
    @DisplayName("동일 작업 식별자와 기대 회차를 전달하고 목표 회차를 확인한다")
    void sendsOriginalOperation() {
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/submissions/2001/retry"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.context").value("EXAM"))
            .andExpect(jsonPath("$.receiptId").value("4001"))
            .andExpect(jsonPath("$.expectedJudgeAttempt").value(1))
            .andExpect(jsonPath("$.operationId").value("retry-op-001"))
            .andExpect(jsonPath("$.actorMemberId").value("7"))
            .andExpect(jsonPath("$.reason").value("재처리"))
            .andRespond(withSuccess("""
                {"submissionId":"2001","judgeAttempt":2,"status":"QUEUED"}
                """, MediaType.APPLICATION_JSON));
        var client = new ExamRetryClient(builder.build());

        var result = client.retry(2001, request());

        assertThat(result.judgeAttempt()).isEqualTo(2);
        server.verify();
    }

    @Test
    @DisplayName("요청한 제출과 다른 원격 응답은 성공으로 처리하지 않는다")
    void rejectsMismatchedResponse() {
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/submissions/2001/retry"))
            .andRespond(withSuccess("""
                {"submissionId":"2002","judgeAttempt":2,"status":"QUEUED"}
                """, MediaType.APPLICATION_JSON));
        var client = new ExamRetryClient(builder.build());

        assertThatThrownBy(() -> client.retry(2001, request())).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    @DisplayName("기대 회차와 다른 목표 회차는 성공으로 처리하지 않는다")
    void rejectsUnexpectedAttempt() {
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/submissions/2001/retry"))
            .andRespond(withSuccess("""
                {"submissionId":"2001","judgeAttempt":3,"status":"QUEUED"}
                """, MediaType.APPLICATION_JSON));
        var client = new ExamRetryClient(builder.build());

        assertThatThrownBy(() -> client.retry(2001, request())).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    @DisplayName("일시적인 게이트웨이 오류 뒤 같은 작업을 재요청한다")
    void retriesSameOperation() {
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/submissions/2001/retry"))
            .andExpect(jsonPath("$.operationId").value("retry-op-001"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/submissions/2001/retry"))
            .andExpect(jsonPath("$.operationId").value("retry-op-001"))
            .andExpect(jsonPath("$.expectedJudgeAttempt").value(1))
            .andRespond(withSuccess("""
                {"submissionId":"2001","judgeAttempt":2,"status":"QUEUED"}
                """, MediaType.APPLICATION_JSON));
        var client = new ExamRetryClient(builder.build());

        assertThat(client.retry(2001, request()).judgeAttempt()).isEqualTo(2);
        server.verify();
    }

    private ExamRetryClient.Request request() {
        return new ExamRetryClient.Request("EXAM", "4001", 1, "retry-op-001", "7", "재처리");
    }
}

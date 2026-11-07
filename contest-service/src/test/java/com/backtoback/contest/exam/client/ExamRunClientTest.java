package com.backtoback.contest.exam.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** Judge의 Run 오류가 명세대로 전달되는지 확인한다. */
class ExamRunClientTest {
    /** RUN_TIMEOUT 응답을 한 번만 받고 다시 요청하지 않는다. */
    @Test
    @DisplayName("Judge RUN_TIMEOUT은 재시도하지 않고 504로 전달한다")
    void timeoutIsNotRetried() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andRespond(
                withStatus(HttpStatus.GATEWAY_TIMEOUT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"code\":\"RUN_TIMEOUT\"}}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request = new ExamRunClient.Request("EXAM", "55", "7", "101", "1001", "java", "code", List.of());
        // when & then
        assertThatThrownBy(() -> client.run(request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> org.assertj.core.api.Assertions
                    .assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.RUN_TIMEOUT)
            );
        server.verify();
    }

    /** 이미 실행 중인 회원의 재요청을 Judge의 429로 전달한다. */
    @Test
    @DisplayName("Judge RUN_IN_PROGRESS는 재시도하지 않고 429로 전달한다")
    void runningIsNotRetried() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"code\":\"RUN_IN_PROGRESS\"}}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request = new ExamRunClient.Request("EXAM", "55", "7", "101", "1001", "java", "code", List.of());
        // when & then
        assertThatThrownBy(() -> client.run(request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> org.assertj.core.api.Assertions
                    .assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.RUN_IN_PROGRESS)
            );
        server.verify();
    }
}

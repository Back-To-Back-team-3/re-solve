package com.backtoback.contest.exam.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** Judge의 Run 오류가 명세대로 전달되는지 확인한다. */
class ExamRunClientTest {
    /** 고정 회차와 사용자 인수를 Judge에 전달하고 정상 결과를 반환한다. */
    @Test
    @DisplayName("고정 회차의 유효한 입력은 Judge 실행 결과를 반환한다")
    void returnsResultForPinnedRevision() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.customTests[0].args[0]").value(3))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"results\":[],\"completedAt\":\"2026-11-12T12:00:00+09:00\"}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request
            = new ExamRunClient.Request(
                "EXAM",
                "55",
                "7",
                "101",
                "1001",
                "java",
                "code",
                List.of(new ExamRunClient.CustomTest(List.of(3)))
            );

        // when
        var result = client.run(request);

        // then
        assertThat(result.results()).isEmpty();
        assertThat(result.completedAt()).isEqualTo("2026-11-12T12:00:00+09:00");
        server.verify();
    }

    /** Judge의 입력 오류만 공개 입력 오류로 변환한다. */
    @Test
    @DisplayName("Judge의 COMMON_INVALID_REQUEST는 400 입력 오류로 전달한다")
    void propagatesInvalidRequest() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"error\":{\"code\":\"COMMON_INVALID_REQUEST\"}}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request = new ExamRunClient.Request("EXAM", "55", "7", "101", "1001", "java", "code", List.of());

        // when & then
        assertThatThrownBy(() -> client.run(request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        server.verify();
    }

    /** 오류 메시지에 코드가 포함돼도 다른 오류 코드는 의존성 장애로 처리한다. */
    @Test
    @DisplayName("다른 400 오류의 메시지에 입력 오류 코드가 있어도 503으로 처리한다")
    void rejectsOtherBadRequest() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"code\":\"OTHER\",\"message\":\"COMMON_INVALID_REQUEST\"}}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request = new ExamRunClient.Request("EXAM", "55", "7", "101", "1001", "java", "code", List.of());

        // when & then
        assertThatThrownBy(() -> client.run(request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
        server.verify();
    }

    /** RUN_TIMEOUT 응답을 한 번만 받고 다시 요청하지 않는다. */
    @Test
    @DisplayName("Judge RUN_TIMEOUT은 재시도하지 않고 504로 전달한다")
    void timeoutIsNotRetried() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.context").value("EXAM"))
            .andExpect(jsonPath("$.contextId").value("55"))
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.problemId").value("101"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.language").value("java"))
            .andExpect(jsonPath("$.sourceCode").value("code"))
            .andExpect(jsonPath("$.customTests[0].args[0]").value(3))
            .andRespond(
                withStatus(HttpStatus.GATEWAY_TIMEOUT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"code\":\"RUN_TIMEOUT\"}}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request
            = new ExamRunClient.Request(
                "EXAM",
                "55",
                "7",
                "101",
                "1001",
                "java",
                "code",
                List.of(new ExamRunClient.CustomTest(List.of(3)))
            );
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

    /** 고정 회차에 지원되지 않는 언어의 Judge 응답을 전달한다. */
    @Test
    @DisplayName("Judge의 지원하지 않는 언어 오류는 400으로 전달한다")
    void unsupportedLanguage() {
        // given
        var builder = RestClient.builder().baseUrl("http://judge-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://judge-test/internal/v1/judge/runs"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"code\":\"UNSUPPORTED_LANGUAGE\"}}")
            );
        var client = new ExamRunClient(remaining -> builder.build());
        var request = new ExamRunClient.Request("EXAM", "55", "7", "101", "1001", "java", "code", List.of());
        // when & then
        assertThatThrownBy(() -> client.run(request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> org.assertj.core.api.Assertions
                    .assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.UNSUPPORTED_LANGUAGE)
            );
        server.verify();
    }
}

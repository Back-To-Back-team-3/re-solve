package com.backtoback.problem.problem.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.problem.global.exception.ProblemException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** 원격 기본 코드의 언어 집합과 원문 타입을 검증한다. */
class JudgeStarterCodeClientTest {
    /** 요청 언어 순서로 기본 코드를 반환한다. */
    @Test
    @DisplayName("기본 코드 응답은 요청 언어 순서로 정렬한다")
    void preservesLanguages() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("/internal/v1/judge/starter-codes"))
            .andRespond(
                withSuccess(
                    "{\"starterCodes\":[{\"language\":\"java\",\"sourceCode\":\"class Solution {}\"}]}",
                    MediaType.APPLICATION_JSON
                )
            );
        var client = new JudgeStarterCodeClient(builder.build(), CircuitBreaker.ofDefaults("starter-test"));
        assertThat(client.read(Map.of("name", "solution"), List.of("java")).getFirst().sourceCode())
            .isEqualTo("class Solution {}");
        server.verify();
    }

    /**
     * 누락·중복·자료형 오류를 의존 장애로 처리한다.
     *
     * @param payload 잘못된 응답
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"starterCodes\":[]}",
            "{\"starterCodes\":[{\"language\":\"java\",\"sourceCode\":7}]}",
            "{\"starterCodes\":[{\"language\":\"java\",\"sourceCode\":\"a\"},"
                + "{\"language\":\"java\",\"sourceCode\":\"b\"}]}"
        }
    )
    @DisplayName("기본 코드의 누락 언어·중복 언어·문자열 아닌 원문은 거부한다")
    void rejectsInvalidResponse(String payload) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("/internal/v1/judge/starter-codes"))
            .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        var client = new JudgeStarterCodeClient(builder.build(), CircuitBreaker.ofDefaults("starter-invalid"));
        assertThatThrownBy(() -> client.read(Map.of("name", "solution"), List.of("java")))
            .isInstanceOf(ProblemException.class);
    }
}

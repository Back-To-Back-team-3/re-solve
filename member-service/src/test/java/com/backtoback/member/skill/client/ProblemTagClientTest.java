package com.backtoback.member.skill.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

class ProblemTagClientTest {

    private static final String TAG_URL = "http://problem-service/internal/v1/tags/3";

    private MockRestServiceServer server;
    private ProblemTagClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://problem-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProblemTagClient(builder.build());
    }

    @Test
    @DisplayName("problem이 200으로 응답하면 태그가 있다")
    void existingTag() {
        server
            .expect(requestTo(TAG_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"tagId\":\"3\",\"name\":\"해시\"}", MediaType.APPLICATION_JSON));

        assertThat(client.exists(3L)).isTrue();
    }

    @Test
    @DisplayName("problem이 404 PROBLEM_TAG_NOT_FOUND로 응답하면 태그가 없다")
    void missingTag() {
        server
            .expect(requestTo(TAG_URL))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        "{\"success\":false,\"error\":{\"code\":\"PROBLEM_TAG_NOT_FOUND\",\"message\":\"태그를 찾을 수 없음\"}}"
                    )
            );

        assertThat(client.exists(3L)).isFalse();
    }

    @Test
    @DisplayName("다른 tagId의 200·204·경로가 없는 404·서버 오류·통신 오류는 태그 없음이 아니라 COMMON_DEPENDENCY_UNAVAILABLE")
    void failsClosedOnOtherResponses() {
        server
            .expect(requestTo(TAG_URL))
            .andRespond(withSuccess("{\"tagId\":\"4\",\"name\":\"스택/큐\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(TAG_URL)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server
            .expect(requestTo(TAG_URL))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"error\":{\"code\":\"COMMON_INVALID_REQUEST\"}}")
            );
        server.expect(requestTo(TAG_URL)).andRespond(withServerError());
        server.expect(requestTo(TAG_URL)).andRespond(withException(new IOException("connection refused")));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.exists(3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        server.verify();
    }
}

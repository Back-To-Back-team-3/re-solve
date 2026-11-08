package com.backtoback.contest.operation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 스터디·문제의 원래 작업 조회를 확인한다. */
class ResourceOperationClientTest {
    @Test
    @DisplayName("문제의 원래 작업 결과를 직접 응답으로 조회한다")
    void readsProblemOperation() {
        var builder = RestClient.builder().baseUrl("http://problem-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://problem-test/internal/v1/problems/101/operations/op"))
            .andRespond(withSuccess("""
                {"operationId":"op","state":"COMMITTED","sourceVersion":"5","resourceStatus":"PRIVATE"}
                """, MediaType.APPLICATION_JSON));
        var client = new ResourceOperationClient(builder.build(), builder.build());
        assertThat(client.find("PROBLEM", 101, "op").state()).isEqualTo("COMMITTED");
        server.verify();
    }

    @Test
    @DisplayName("스터디 작업이 없으면 자동 취소로 변환하지 않는다")
    void studyNotFoundIsUnknown() {
        var builder = RestClient.builder().baseUrl("http://study-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://study-test/internal/v1/studies/12/operations/op"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        var client = new ResourceOperationClient(builder.build(), builder.build());
        assertThatThrownBy(() -> client.find("STUDY", 12, "op")).isInstanceOf(RestClientResponseException.class);
        server.verify();
    }
}

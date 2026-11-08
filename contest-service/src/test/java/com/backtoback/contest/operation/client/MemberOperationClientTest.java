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

/** 회원 탈퇴 원격 작업 조회의 요청과 실패를 확인한다. */
class MemberOperationClientTest {
    @Test
    @DisplayName("회원과 원래 작업 ID로 조회하고 작업 결과를 반환한다")
    void readsOriginalOperation() {
        var builder = RestClient.builder().baseUrl("http://member-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://member-test/internal/v1/members/7/operations/op-1")).andRespond(withSuccess("""
            {"operationId":"op-1","state":"COMMITTED","sourceVersion":"5","resourceStatus":"WITHDRAWN"}
            """, MediaType.APPLICATION_JSON));

        var result = new MemberOperationClient(builder.build()).find(7, "op-1");

        assertThat(result.state()).isEqualTo("COMMITTED");
        assertThat(result.sourceVersion()).isEqualTo("5");
        server.verify();
    }

    @Test
    @DisplayName("존재하지 않는 원격 작업은 자동 취소로 변환하지 않는다")
    void notFoundIsNotAbort() {
        var builder = RestClient.builder().baseUrl("http://member-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://member-test/internal/v1/members/7/operations/op-1"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> new MemberOperationClient(builder.build()).find(7, "op-1"))
            .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    @DisplayName("다른 작업의 응답은 유효한 조회 결과로 사용하지 않는다")
    void rejectsDifferentOperation() {
        var builder = RestClient.builder().baseUrl("http://member-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://member-test/internal/v1/members/7/operations/op-1")).andRespond(withSuccess("""
            {"operationId":"op-2","state":"ABORTED","sourceVersion":"4","resourceStatus":"ACTIVE"}
            """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new MemberOperationClient(builder.build()).find(7, "op-1"))
            .isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}

package com.backtoback.study.member.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.replica.domain.MemberStatus;

/** member 내부 상태 조회 응답 해석과 fail-closed 변환을 검증한다. */
class MemberStatusClientTest {

    private static final String STATUS_URL = "http://member-test/internal/v1/members/7/status";

    private MemberStatusClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://member-test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MemberStatusClient(builder.build());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("정상 응답이면 공통 응답으로 감싸지 않은 본문의 회원 상태를 반환한다")
    void returnsStatus() {
        server.expect(requestTo(STATUS_URL)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
            {"memberId": "7", "status": "SUSPENDED", "role": "USER"}
            """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchStatus(7L)).isEqualTo(MemberStatus.SUSPENDED);
        server.verify();
    }

    @Test
    @DisplayName("member가 5xx를 반환하면 COMMON_DEPENDENCY_UNAVAILABLE로 거절한다")
    void serverErrorIsDependencyUnavailable() {
        server.expect(requestTo(STATUS_URL)).andRespond(withServerError());

        assertDependencyUnavailable();
    }

    @Test
    @DisplayName("member가 회원 없음(404)을 반환하면 COMMON_DEPENDENCY_UNAVAILABLE로 거절한다")
    void notFoundIsDependencyUnavailable() {
        server.expect(requestTo(STATUS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertDependencyUnavailable();
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면 COMMON_DEPENDENCY_UNAVAILABLE로 거절한다")
    void emptyBodyIsDependencyUnavailable() {
        server.expect(requestTo(STATUS_URL)).andRespond(withSuccess());

        assertDependencyUnavailable();
    }

    @Test
    @DisplayName("다른 회원의 응답이 오면 COMMON_DEPENDENCY_UNAVAILABLE로 거절한다")
    void otherMemberIsDependencyUnavailable() {
        server.expect(requestTo(STATUS_URL)).andRespond(withSuccess("""
            {"memberId": "8", "status": "ACTIVE", "role": "USER"}
            """, MediaType.APPLICATION_JSON));

        assertDependencyUnavailable();
    }

    @Test
    @DisplayName("계약 밖 상태 값이 오면 COMMON_DEPENDENCY_UNAVAILABLE로 거절한다")
    void unknownStatusIsDependencyUnavailable() {
        server.expect(requestTo(STATUS_URL)).andRespond(withSuccess("""
            {"memberId": "7", "status": "LOCKED", "role": "USER"}
            """, MediaType.APPLICATION_JSON));

        assertDependencyUnavailable();
    }

    @Test
    @DisplayName("트랜잭션 안에서 호출하면 원격 조회 없이 IllegalStateException을 던진다")
    void rejectsCallInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> client.fetchStatus(7L)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    private void assertDependencyUnavailable() {
        assertThatThrownBy(() -> client.fetchStatus(7L))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }
}

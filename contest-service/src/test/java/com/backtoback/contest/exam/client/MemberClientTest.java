package com.backtoback.contest.exam.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestException;

/**
 * 회원 내부 API 응답에서 필수 스냅샷 필드와 의존성 실패 처리를 검증한다.
 */
class MemberClientTest {
    @Test
    @DisplayName("확장된 회원 응답의 원천 버전과 표시 정보를 읽는다")
    void readsSnapshot() {
        var builder = RestClient.builder().baseUrl("http://member-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://member-test/internal/v1/members/7/status")).andRespond(withSuccess("""
            {"memberId":"7","status":"ACTIVE","role":"USER","sourceVersion":2,
             "nickname":"이름","profileImageUrl":null}
            """, MediaType.APPLICATION_JSON));
        var response = new MemberClient(builder.build()).fetch(7L);
        assertThat(response.sourceVersion()).isEqualTo(2L);
        assertThat(response.nickname()).isEqualTo("이름");
        assertThat(response.profileImageUrl()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("원천 버전이 빠진 기존 응답은 의존 서비스 실패로 거절한다")
    void rejectsIncompleteResponse() {
        var builder = RestClient.builder().baseUrl("http://member-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://member-test/internal/v1/members/7/status"))
            .andRespond(
                withSuccess("{\"memberId\":\"7\",\"status\":\"ACTIVE\",\"role\":\"USER\"}", MediaType.APPLICATION_JSON)
            );
        assertThatThrownBy(() -> new MemberClient(builder.build()).fetch(7L))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        server.verify();
    }
}

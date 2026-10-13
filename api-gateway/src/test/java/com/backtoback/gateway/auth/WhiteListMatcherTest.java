package com.backtoback.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class WhiteListMatcherTest {

    private final WhiteListMatcher matcher = new WhiteListMatcher();

    @Test
    @DisplayName("Actuator 헬스체크는 공개 경로로 판정된다")
    void actuatorIsWhiteList() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
        assertThat(matcher.isWhiteList(request)).isTrue();
    }

    @Test
    @DisplayName("인증 관련 시작 및 토큰 교환은 공개 경로로 판정된다")
    void authEndpointsAreWhiteList() {
        MockServerHttpRequest githubLogin = MockServerHttpRequest.get("/api/v1/auth/github").build();
        MockServerHttpRequest tokenExchange = MockServerHttpRequest.post("/api/v1/auth/token").build();

        assertThat(matcher.isWhiteList(githubLogin)).isTrue();
        assertThat(matcher.isWhiteList(tokenExchange)).isTrue();
    }

    @Test
    @DisplayName("로그아웃 경로는 토큰 검증이 필요하므로 공개 경로가 아니다")
    void logoutIsNotWhiteList() {
        MockServerHttpRequest logout = MockServerHttpRequest.post("/api/v1/auth/logout").build();
        assertThat(matcher.isWhiteList(logout)).isFalse();
    }

    @Test
    @DisplayName("문제, 대회, 스터디의 GET 조회는 공개 경로로 판정된다")
    void publicGetQueriesAreWhiteList() {
        MockServerHttpRequest problems = MockServerHttpRequest.get("/api/v1/problems").build();
        MockServerHttpRequest contests = MockServerHttpRequest.get("/api/v1/contests").build();
        MockServerHttpRequest studies = MockServerHttpRequest.get("/api/v1/studies").build();
        MockServerHttpRequest languages = MockServerHttpRequest.get("/api/v1/judge/languages").build();

        assertThat(matcher.isWhiteList(problems)).isTrue();
        assertThat(matcher.isWhiteList(contests)).isTrue();
        assertThat(matcher.isWhiteList(studies)).isTrue();
        assertThat(matcher.isWhiteList(languages)).isTrue();
    }

    @Test
    @DisplayName("문제 등록 및 제출 등 쓰기 작업은 공개 경로가 아니다")
    void writeRequestsAreNotWhiteList() {
        MockServerHttpRequest createProblem = MockServerHttpRequest.post("/api/v1/problems").build();
        MockServerHttpRequest submitCode = MockServerHttpRequest.post("/api/v1/submissions").build();

        assertThat(matcher.isWhiteList(createProblem)).isFalse();
        assertThat(matcher.isWhiteList(submitCode)).isFalse();
    }

    @Test
    @DisplayName("관리자 경로는 GET이라도 공개 경로가 아니다")
    void adminPathsAreNotWhiteList() {
        MockServerHttpRequest adminProblems = MockServerHttpRequest.get("/api/v1/admin/problems").build();
        assertThat(matcher.isWhiteList(adminProblems)).isFalse();
    }

    @Test
    @DisplayName("내부 API 경로는 올바르게 internal 경로로 식별된다")
    void identifiesInternalPath() {
        assertThat(matcher.isInternalPath("/internal/v1/submissions/accepted")).isTrue();
        assertThat(matcher.isInternalPath("/api/v1/internal/test")).isTrue();
        assertThat(matcher.isInternalPath("/api/v1/problems")).isFalse();
    }
}

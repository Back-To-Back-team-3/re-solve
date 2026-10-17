package com.backtoback.gateway.auth;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 게이트웨이 인증 예외(White-List) 및 내부 API 경로 매칭 유틸리티.
 */
@Component
public class WhiteListMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 외부에서 접근할 수 없는 내부 서비스 전용 경로인지 여부.
     */
    public boolean isInternalPath(String path) {
        return pathMatcher.match("/internal/**", path) || pathMatcher.match("/**/internal/**", path);
    }

    /**
     * 토큰 없이 접근 가능한 공개 API 경로인지 여부.
     */
    public boolean isWhiteList(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // 1. Actuator 헬스체크 및 메트릭
        if (pathMatcher.match("/actuator/**", path)) {
            return true;
        }

        // 2. 인증 관련 공개 경로 (단, /logout은 로그인 상태에서 호출하므로 제외)
        if (pathMatcher.match("/api/v1/auth/logout", path)) {
            return false;
        }
        if (pathMatcher.match("/api/v1/auth/**", path)) {
            return true;
        }

        // 3. 공개 GET 조회 API
        if (HttpMethod.GET.equals(method)) {
            if (pathMatcher.match("/api/v1/problems/**", path)) {
                return true;
            }
            if (isContestPublicPath(path)) {
                return true;
            }
            if (isStudyPublicPath(path)) {
                return true;
            }
            if (pathMatcher.match("/api/v1/judge/languages", path)) {
                return true;
            }
        }

        return false;
    }

    private boolean isContestPublicPath(String path) {
        // 인증이 필요한 대회/시험 하위 경로 제외 (/me, /drafts, /problems, /results, /events)
        if (pathMatcher.match("/api/v1/contests/**/me/**", path)
            || pathMatcher.match("/api/v1/contests/**/me", path)
            || pathMatcher.match("/api/v1/contests/**/drafts/**", path)
            || pathMatcher.match("/api/v1/contests/**/problems/**", path)
            || pathMatcher.match("/api/v1/contests/**/results/**", path)
            || pathMatcher.match("/api/v1/contests/**/events", path)) {
            return false;
        }

        // 공개 경로: 목록(/contests), 단건 상세(/contests/{id}), 리더보드(/contests/{id}/leaderboard)
        return pathMatcher.match("/api/v1/contests", path)
            || pathMatcher.match("/api/v1/contests/*", path)
            || pathMatcher.match("/api/v1/contests/*/leaderboard", path);
    }

    private boolean isStudyPublicPath(String path) {
        // 인증이 필요한 스터디 하위 경로 제외 (/me, /recommendations, /assignments, /posts, /memberships, /exams, /drafts)
        if (pathMatcher.match("/api/v1/studies/me", path)
            || pathMatcher.match("/api/v1/studies/me/**", path)
            || pathMatcher.match("/api/v1/studies/recommendations", path)
            || pathMatcher.match("/api/v1/studies/**/assignments/**", path)
            || pathMatcher.match("/api/v1/studies/**/posts/**", path)
            || pathMatcher.match("/api/v1/studies/**/memberships/**", path)
            || pathMatcher.match("/api/v1/studies/**/exams/**", path)
            || pathMatcher.match("/api/v1/studies/**/drafts/**", path)) {
            return false;
        }

        // 공개 경로: 스터디 목록(/studies), 단건 상세(/studies/{id})
        return pathMatcher.match("/api/v1/studies", path)
            || pathMatcher.match("/api/v1/studies/*", path);
    }
}

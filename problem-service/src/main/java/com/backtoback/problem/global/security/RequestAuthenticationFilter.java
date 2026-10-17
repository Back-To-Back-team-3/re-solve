package com.backtoback.problem.global.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backtoback.problem.global.security.user.GatewayUser;
import com.backtoback.problem.global.security.user.UserRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RequestAuthenticationFilter extends OncePerRequestFilter {
    private static final int MAX_USER_ID_LENGTH = 128;

    /**
     * 외부 API 요청의 Gateway 사용자 정보를 인증 컨텍스트에 반영한다.
     *
     * @param request 현재 HTTP 요청
     * @param response 현재 HTTP 응답
     * @param chain 다음 필터와 요청 처리기로 이어지는 체인
     * @throws ServletException 후속 요청 처리에서 서블릿 오류가 발생한 경우
     * @throws IOException 후속 요청 처리 중 입출력 오류가 발생한 경우
     * @throws BadCredentialsException Gateway 사용자 정보가 일부 누락되거나 유효하지 않은 경우
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException,
        IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            authenticateUser(request);
        }
        chain.doFilter(request, response);
    }

    /**
     * Gateway 헤더를 검증하고 사용자 인증 정보를 저장한다.
     * 사용자 ID와 역할 헤더가 모두 없으면 인증 정보를 설정하지 않는다.
     *
     * @param request Gateway 사용자 ID와 역할 헤더를 포함한 요청
     * @throws BadCredentialsException 사용자 ID 또는 역할이 유효하지 않은 경우
     */
    private void authenticateUser(HttpServletRequest request) {
        // Gateway가 외부 헤더를 제거한 후 전달한다. 서비스 포트는 내부 네트워크에서만 접근해야 한다.
        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");
        if (userId == null && role == null) {
            return;
        }
        if (userId == null || userId.isBlank() || userId.length() > MAX_USER_ID_LENGTH) {
            throw new BadCredentialsException("잘못된 게이트웨이 인증");
        }
        UserRole userRole = parseRole(role);
        var principal = new GatewayUser(userId, userRole);
        var authentication
            = new PreAuthenticatedAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userRole.name()))
            );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * Gateway가 전달한 역할 문자열을 지원하는 사용자 역할로 변환한다.
     *
     * @param role Gateway가 전달한 역할 문자열
     * @return 문자열에 대응하는 사용자 역할
     * @throws BadCredentialsException 역할이 없거나 지원하지 않는 값인 경우
     */
    private UserRole parseRole(String role) {
        if (role == null) {
            throw new BadCredentialsException("잘못된 게이트웨이 인증");
        }
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("잘못된 게이트웨이 인증");
        }
    }
}

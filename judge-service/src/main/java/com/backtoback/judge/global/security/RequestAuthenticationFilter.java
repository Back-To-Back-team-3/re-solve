package com.backtoback.judge.global.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backtoback.judge.global.security.user.GatewayUser;
import com.backtoback.judge.global.security.user.UserRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RequestAuthenticationFilter extends OncePerRequestFilter {
    private static final int MAX_USER_ID_LENGTH = 128;

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

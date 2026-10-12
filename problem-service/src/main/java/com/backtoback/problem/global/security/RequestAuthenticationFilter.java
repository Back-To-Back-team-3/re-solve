package com.backtoback.problem.global.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RequestAuthenticationFilter extends OncePerRequestFilter {
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
        if (userId == null || userId.isBlank() || userId.length() > 128
            || !("USER".equals(role) || "ADMIN".equals(role))) {
            throw new BadCredentialsException("Invalid gateway identity");
        }
        var principal = new GatewayUser(userId, role);
        var authentication
            = new PreAuthenticatedAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

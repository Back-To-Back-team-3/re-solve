package com.backtoback.problem.global.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.backtoback.problem.global.api.ApiErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class SecurityAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper;

    public SecurityAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
        throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper
            .writeValue(response.getOutputStream(), ApiErrorResponse.of("AUTH_TOKEN_INVALID", "인증 정보가 없거나 유효하지 않습니다."));
    }
}

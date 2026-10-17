package com.backtoback.problem.global.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.backtoback.problem.global.api.ApiErrorResponse;
import com.backtoback.problem.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class SecurityAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper;

    /**
     * 공통 보안 오류 응답을 작성할 처리기를 생성한다.
     *
     * @param mapper JSON 응답을 직렬화할 매퍼
     */
    public SecurityAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 인증이 필요한 요청에 공통 형식의 401 오류를 작성한다.
     *
     * @param request 인증 또는 권한 검사에 실패한 요청
     * @param response 오류 상태와 본문을 작성할 응답
     * @param exception 인증 또는 권한 검사에서 발생한 예외
     * @throws IOException 응답을 출력 스트림에 기록하지 못한 경우
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
        throws IOException {
        response.setStatus(ErrorCode.AUTH_TOKEN_INVALID.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(ErrorCode.AUTH_TOKEN_INVALID));
    }
}

package com.backtoback.problem.global.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.backtoback.problem.global.api.ApiErrorResponse;
import com.backtoback.problem.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class SecurityAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper mapper;

    /**
     * 공통 보안 오류 응답을 작성할 처리기를 생성한다.
     *
     * @param mapper JSON 응답을 직렬화할 매퍼
     */
    public SecurityAccessDeniedHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 권한이 부족한 요청에 공통 형식의 403 오류를 작성한다.
     *
     * @param request 인증 또는 권한 검사에 실패한 요청
     * @param response 오류 상태와 본문을 작성할 응답
     * @param exception 인증 또는 권한 검사에서 발생한 예외
     * @throws IOException 응답을 출력 스트림에 기록하지 못한 경우
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
        throws IOException {
        response.setStatus(ErrorCode.AUTH_ACCESS_DENIED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(ErrorCode.AUTH_ACCESS_DENIED));
    }
}

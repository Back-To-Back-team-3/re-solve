package com.backtoback.common.web.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.common.exception.CommonErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 보안 필터 체인의 인증 실패를 공통 401 JSON 응답으로 변환한다.
 * 사용하는 서비스가 보안 체인의 authenticationEntryPoint로 등록해야 한다.
 * 예외 메시지·헤더 원문은 응답에 포함하지 않으며 JWT 만료 판단은 Gateway가 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 실패 본문의 공통 형식</li>
 * <li>에러 코드 표 / Common · Auth: AUTH_TOKEN_INVALID의 401 응답</li>
 * </ul>
 */
public class SecurityAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper;

    /**
     * 서비스의 JSON 설정을 사용하는 인증 실패 처리기를 생성한다.
     *
     * @param mapper 응답을 직렬화할 서비스의 JSON 매퍼
     */
    public SecurityAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 인증 정보가 없거나 유효하지 않은 요청에 고정된 공통 오류를 작성한다.
     *
     * @param request 인증에 실패한 HTTP 요청
     * @param response 상태와 JSON 본문을 작성할 HTTP 응답
     * @param exception 인증 실패 예외; 내부 메시지는 공개하지 않음
     * @throws IOException 응답 본문을 출력 스트림에 기록하지 못한 경우
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
        throws IOException {
        response.setStatus(CommonErrorCode.AUTH_TOKEN_INVALID.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(CommonErrorCode.AUTH_TOKEN_INVALID));
    }
}

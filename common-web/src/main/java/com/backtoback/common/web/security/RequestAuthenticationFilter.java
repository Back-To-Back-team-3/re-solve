package com.backtoback.common.web.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 외부 API의 Gateway 사용자 헤더를 Spring Security 인증 정보로 연결한다.
 * Gateway의 외부 식별 헤더 제거와 서비스 포트의 내부망 격리를 전제로 하며 JWT를 재검증하지 않는다.
 * 두 헤더가 모두 없으면 인증 정보를 설정하지 않고, 공개·인증·관리자 경로는 서비스가 판단한다.
 * 사용하는 서비스는 상태를 저장하지 않는 보안 체인에서 ExceptionTranslationFilter 뒤,
 * AuthorizationFilter 앞에 등록하고 공통 401·403 처리기를 연결해야 한다.
 * 보안 체인 밖의 Servlet 필터로 중복 등록하지 않으며, 요청 종료 시 인증 정보 정리는 보안 체인이 담당한다.
 * 인증 정보는 요청 속성에 저장해 같은 요청의 비동기 재진입에서도 복원하며 세션에는 저장하지 않는다.
 * 보안 체인의 컨텍스트 저장소도 동일한 요청 속성 저장소를 사용해야 한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: Gateway 인증과 사용자 식별·역할 전달</li>
 * <li>API 명세서 v1.1 / §0.6 권한 요약: 서비스별 공개·로그인·관리자 권한 구분</li>
 * <li>서비스 간 인증 방식 / 결정: 내부 API의 서비스 토큰 인증 생략</li>
 * </ul>
 */
public class RequestAuthenticationFilter extends OncePerRequestFilter {
    private static final int MAX_USER_ID_LENGTH = 128;
    private final RequestAttributeSecurityContextRepository contextRepository
        = new RequestAttributeSecurityContextRepository();

    /**
     * 컨텍스트 경로를 제외한 /api/ 요청에만 사용자 인증 정보를 반영한다.
     * 내부 API와 기타 경로는 사용자 헤더를 해석하지 않고 다음 필터로 전달한다.
     *
     * @param request 현재 HTTP 요청
     * @param response 현재 HTTP 응답
     * @param chain 다음 보안 필터와 요청 처리기로 이어지는 체인
     * @throws ServletException 후속 요청 처리에서 Servlet 오류가 발생한 경우
     * @throws IOException 후속 요청 처리 중 입출력 오류가 발생한 경우
     * @throws BadCredentialsException 사용자 헤더가 일부 누락되거나 유효하지 않은 경우
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException,
        IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            authenticateUser(request, response);
        }
        chain.doFilter(request, response);
    }

    /**
     * 사용자 헤더를 검증한 뒤 요청 전용 인증 컨텍스트를 설정한다.
     *
     * @param request Gateway 사용자 ID와 역할 헤더를 조회할 요청
     * @param response 인증 정보 저장에 전달할 현재 응답
     * @throws BadCredentialsException ID가 없거나 공백·길이 초과이거나 역할이 유효하지 않은 경우
     */
    private void authenticateUser(HttpServletRequest request, HttpServletResponse response) {
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
        // 공유된 컨텍스트 객체를 변경하지 않고 현재 요청의 인증 정보를 설정한다.
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    /**
     * Gateway 역할 문자열을 USER 또는 ADMIN으로 변환한다.
     * 대소문자나 공백은 보정하지 않는다.
     *
     * @param role Gateway가 전달한 역할 문자열
     * @return 문자열에 대응하는 서비스 공통 역할
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

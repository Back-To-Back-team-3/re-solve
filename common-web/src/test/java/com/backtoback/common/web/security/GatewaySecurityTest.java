package com.backtoback.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;

import tools.jackson.databind.ObjectMapper;

/**
 * Gateway 헤더가 MVC 사용자 정보와 경로 권한 검사로 연결되는지 검증한다.
 * 테스트용 보안 체인에 공통 필터와 오류 처리기를 등록한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: Gateway 헤더 전달과 내부 API 인증 생략</li>
 * <li>API 명세서 v1.1 / §0.6 권한 요약: 공개·인증·관리자 요청의 구분</li>
 * <li>에러 코드 표 / Common · Auth: 인증 실패 401과 권한 부족 403</li>
 * </ul>
 */
class GatewaySecurityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    /**
     * Servlet 서버 없이 MVC와 Spring Security의 필터 체인을 연결한다.
     */
    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityTestConfiguration.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /**
     * 테스트 컨텍스트를 닫고 현재 스레드의 인증 정보를 정리한다.
     */
    @AfterEach
    void tearDown() {
        context.close();
        SecurityContextHolder.clearContext();
    }

    /**
     * 두 공통 역할의 헤더를 전달해 MVC principal과 요청 종료 후 인증 정보 정리를 확인한다.
     *
     * @param role Gateway에서 전달할 사용자 역할
     * @throws Exception MVC 요청 처리 또는 JSON 읽기에 실패한 경우
     */
    @ParameterizedTest
    @EnumSource(UserRole.class)
    @DisplayName("Gateway 사용자 ID와 역할을 MVC에 전달하고 요청 종료 후 인증 정보를 정리한다")
    void exposesGatewayUserAndClearsContext(UserRole role) throws Exception {
        // given
        var request = userRequest("/api/v1/me", "12", role.name());

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.get("userId").asString()).isEqualTo("12");
        assertThat(json.get("role").asString()).isEqualTo(role.name());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * 일부 헤더 누락과 잘못된 값이 필터 예외로 남지 않고 공통 오류 응답으로 처리되는지 확인한다.
     *
     * @param userId 사용자 ID 헤더; null이면 생략
     * @param role 사용자 역할 헤더; null이면 생략
     * @throws Exception MVC 요청 처리 또는 JSON 읽기에 실패한 경우
     */
    @ParameterizedTest
    @MethodSource("invalidHeaders")
    @DisplayName("사용자 헤더가 일부 누락되거나 유효하지 않으면 공통 401을 반환한다")
    void rejectsInvalidHeaders(String userId, String role) throws Exception {
        // given
        var request = userRequest("/api/v1/me", userId, role);

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();

        // then
        assertErrorResponse(response, 401, "AUTH_TOKEN_INVALID");
    }

    /**
     * 두 헤더가 모두 없을 때 인증이 필요한 경로의 접근을 차단하는지 확인한다.
     *
     * @throws Exception MVC 요청 처리 또는 JSON 읽기에 실패한 경우
     */
    @Test
    @DisplayName("로그인 필요 경로에 사용자 헤더 없이 접근하면 공통 401을 반환한다")
    void rejectsUnauthenticatedRequest() throws Exception {
        // given
        var request = get("/api/v1/me");

        // when
        var response = mockMvc.perform(request).andReturn().getResponse();

        // then
        assertErrorResponse(response, 401, "AUTH_TOKEN_INVALID");
    }

    /**
     * 같은 관리자 경로에서 일반 사용자 거절과 관리자 허용을 함께 확인한다.
     *
     * @throws Exception MVC 요청 처리 또는 JSON 읽기에 실패한 경우
     */
    @Test
    @DisplayName("관리자 경로는 USER에게 공통 403을 반환하고 ADMIN을 허용한다")
    void enforcesAdminRole() throws Exception {
        // given
        var userRequest = userRequest("/api/v1/admin/probe", "12", "USER");
        var adminRequest = userRequest("/api/v1/admin/probe", "12", "ADMIN");

        // when
        var userResponse = mockMvc.perform(userRequest).andReturn().getResponse();
        var adminResponse = mockMvc.perform(adminRequest).andReturn().getResponse();

        // then
        assertErrorResponse(userResponse, 403, "AUTH_ACCESS_DENIED");
        assertThat(adminResponse.getStatus()).isEqualTo(200);
    }

    /**
     * 공개 경로는 헤더 없이 허용하고 내부 경로에서는 사용자 헤더를 해석하지 않는지 확인한다.
     *
     * @throws Exception MVC 요청 처리에 실패한 경우
     */
    @Test
    @DisplayName("공개 경로는 사용자 헤더를 요구하지 않고 내부 경로는 사용자 헤더 검증을 생략한다")
    void permitsPublicAndInternalRequests() throws Exception {
        // given
        var publicRequest = get("/api/v1/public");
        var internalRequest = userRequest("/internal/v1/probe", " ", "UNKNOWN");

        // when
        var publicResponse = mockMvc.perform(publicRequest).andReturn().getResponse();
        var internalResponse = mockMvc.perform(internalRequest).andReturn().getResponse();

        // then
        assertThat(publicResponse.getStatus()).isEqualTo(200);
        assertThat(internalResponse.getStatus()).isEqualTo(200);
    }

    /**
     * 사용자 ID·역할의 누락, 공백 ID와 지원하지 않는 역할을 제공한다.
     *
     * @return 인증 실패로 처리해야 하는 헤더 조합
     */
    private static Stream<Arguments> invalidHeaders() {
        return Stream
            .of(
                Arguments.of(null, "USER"),
                Arguments.of("12", null),
                Arguments.of(" ", "USER"),
                Arguments.of("12", "UNKNOWN")
            );
    }

    /**
     * 지정된 사용자 헤더만 포함하는 GET 요청을 생성한다.
     *
     * @param path 테스트 요청 경로
     * @param userId 사용자 ID 헤더; null이면 생략
     * @param role 사용자 역할 헤더; null이면 생략
     * @return 헤더 설정을 마친 요청 빌더
     */
    private MockHttpServletRequestBuilder userRequest(String path, String userId, String role) {
        var request = get(path);
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        if (role != null) {
            request.header("X-User-Role", role);
        }
        return request;
    }

    /**
     * 보안 오류의 상태·JSON 형식·문자 인코딩을 공통 계약과 대조한다.
     *
     * @param response 필터 체인에서 반환한 응답
     * @param status 기대 HTTP 상태
     * @param errorCode 기대 오류 코드
     * @throws Exception JSON 본문을 읽지 못한 경우
     */
    private void assertErrorResponse(MockHttpServletResponse response, int status, String errorCode) throws Exception {
        var json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").get("code").asString()).isEqualTo(errorCode);
        assertThat(json.get("error").get("details")).isEqualTo(mapper.readTree("[]"));
    }

    /**
     * 공통 필터와 오류 처리기를 사용하는 테스트용 경로 정책을 구성한다.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import(ProbeController.class)
    static class SecurityTestConfiguration {
        /**
         * MVC와 공통 오류 처리기에 사용할 JSON 매퍼를 제공한다.
         *
         * @return 테스트용 JSON 매퍼
         */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * 공개·내부·관리자·로그인 경로를 구분하고 공통 보안 구성 요소를 연결한다.
         *
         * @param http 테스트의 HTTP 보안 설정
         * @param mapper 보안 오류 응답을 직렬화할 매퍼
         * @return 인증 상태를 세션에 저장하지 않는 필터 체인
         * @throws Exception 필터 체인을 구성하지 못한 경우
         */
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                    authorize -> authorize
                        .requestMatchers("/api/v1/public", "/internal/**")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/v1/me")
                        .authenticated()
                        .anyRequest()
                        .denyAll()
                )
                .exceptionHandling(
                    errors -> errors
                        .authenticationEntryPoint(new SecurityAuthenticationEntryPoint(mapper))
                        .accessDeniedHandler(new SecurityAccessDeniedHandler(mapper))
                )
                .addFilterAfter(new RequestAuthenticationFilter(), ExceptionTranslationFilter.class)
                .build();
        }
    }

    /**
     * 인증 principal과 경로 접근 결과를 확인할 MVC 요청 처리기다.
     */
    @RestController
    static class ProbeController {
        /**
         * 필터가 저장한 사용자 정보를 MVC 인자로 받아 반환한다.
         *
         * @param user 현재 요청의 인증 principal
         * @return Gateway 사용자 ID와 역할
         */
        @GetMapping("/api/v1/me")
        GatewayUser me(@AuthenticationPrincipal GatewayUser user) {
            return user;
        }

        /**
         * 공개·내부·관리자 경로의 접근 허용 결과를 반환한다.
         *
         * @return 접근이 허용된 요청의 고정 응답
         */
        @GetMapping(
            {
                "/api/v1/public",
                "/internal/v1/probe",
                "/api/v1/admin/probe"
            }
        )
        String probe() {
            return "ok";
        }
    }
}

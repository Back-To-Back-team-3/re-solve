package com.backtoback.member.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.backtoback.common.web.security.RequestAuthenticationFilter;
import com.backtoback.common.web.security.SecurityAccessDeniedHandler;
import com.backtoback.common.web.security.SecurityAuthenticationEntryPoint;

import tools.jackson.databind.ObjectMapper;

/**
 * JWT 검증은 API Gateway가 맡고, 서비스는 Gateway가 전달한 {@code X-User-Id}·{@code X-User-Role}로 판단한다.
 * GitHub 로그인은 Redis에 보관하는 state·로그인 교환 코드 흐름(API 명세서 1.1)을 직접 구현하므로
 * Spring Security의 세션 기반 oauth2Login은 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(
                context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository())
            )
            .authorizeHttpRequests(
                authorize -> authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // Gateway가 라우팅하지 않아 서비스에 직접 붙는 로컬 개발에서만 열린다.
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                    .permitAll()
                    // 내부 API는 Nginx·Gateway가 외부 접근을 막는다(서비스 간 인증 협의 제안 2).
                    .requestMatchers("/internal", "/internal/**")
                    .permitAll()
                    // Gateway WhiteListMatcher와 같게 로그아웃만 인증하고 나머지 인증 API는 공개한다.
                    .requestMatchers("/api/v1/auth/logout")
                    .authenticated()
                    .requestMatchers("/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin", "/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/**")
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

package com.backtoback.study.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
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
 * Gateway가 전달한 사용자 헤더로 study 경로의 로그인·관리자 여부만 판단한다 (API 명세서 0.6 권한 요약).
 * 스터디별 역할·구성원 여부와 {@code SUSPENDED} 차단은 이 체인이 아니라 서비스 코드에서 확인한다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, Environment environment)
        throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 비동기 재진입에서도 인증을 복원하도록 세션 대신 요청 속성에 저장한다 (공통 모듈 적용 가이드 3).
            .securityContext(
                context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository())
            )
            .exceptionHandling(
                errors -> errors
                    .authenticationEntryPoint(new SecurityAuthenticationEntryPoint(mapper))
                    .accessDeniedHandler(new SecurityAccessDeniedHandler(mapper))
            )
            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                // 내부 API는 서비스 토큰 없이 호출한다 (결정 기록 2026-10-14 서비스 간 인증 방식).
                authorize.requestMatchers("/internal/**").permitAll();
                if (environment.acceptsProfiles(Profiles.of("local"))) {
                    authorize.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                }
                authorize
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    // 아래 공개 경로 /studies/*에 먼저 걸리지 않도록 로그인 필요 경로를 앞에 둔다.
                    .requestMatchers(HttpMethod.GET, "/api/v1/studies/me", "/api/v1/studies/recommendations")
                    .authenticated()
                    // 공개 조회도 인증 헤더가 있으면 사용자 정보를 함께 쓴다 (API 명세서 5.1.2).
                    .requestMatchers(HttpMethod.GET, "/api/v1/studies", "/api/v1/studies/*")
                    .permitAll()
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll();
            })
            .addFilterAfter(new RequestAuthenticationFilter(), ExceptionTranslationFilter.class)
            .build();
    }
}

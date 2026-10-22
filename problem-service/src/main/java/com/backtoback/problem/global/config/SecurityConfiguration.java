package com.backtoback.problem.global.config;

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

/** Gateway 헤더 기반 외부 사용자 인증과 내부 API 접근 정책을 구성한다. (서비스 간 인증 방식) */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * Gateway 사용자 인증과 경로별 접근 정책을 필터 체인에 적용한다.
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
            .securityContext(
                context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository())
            )
            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                // 내부 API는 신뢰된 내부 네트워크에서 호출하므로 사용자 토큰 인증을 생략한다.
                authorize.requestMatchers("/internal", "/internal/**").permitAll();
                authorize
                    .requestMatchers("/api/v1/admin", "/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll();
            })
            .exceptionHandling(
                errors -> errors
                    .authenticationEntryPoint(new SecurityAuthenticationEntryPoint(mapper))
                    .accessDeniedHandler(new SecurityAccessDeniedHandler(mapper))
            )
            .addFilterAfter(new RequestAuthenticationFilter(), ExceptionTranslationFilter.class)
            .build();
    }
}

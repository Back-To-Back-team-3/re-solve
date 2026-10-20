package com.backtoback.problem.global.security;

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

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * Gateway 사용자 정보를 기준으로 외부 API 권한을 검사하는 필터 체인을 구성한다.
     * 내부 API는 신뢰된 내부 네트워크에서 별도 인증 없이 허용한다.
     *
     * @param http Spring Security HTTP 보안 설정
     * @param mapper 보안 오류 응답을 직렬화할 매퍼
     * @return 상태를 저장하지 않는 보안 필터 체인
     * @throws Exception 보안 필터 체인을 구성하지 못한 경우
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            // stateless
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(
                context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository())
            )
            // request 인증 설정
            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                // 내부 서비스 통신은 신뢰된 내부 네트워크에서 호출한다.
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

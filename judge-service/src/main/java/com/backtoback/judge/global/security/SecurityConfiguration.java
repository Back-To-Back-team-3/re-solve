package com.backtoback.judge.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

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
            // request 인증 설정
            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                // 내부 서비스 통신 인증 수단이 생기기 전까진 내부 API 인증을 생략한다.
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

package com.backtoback.contest.global.config;

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

import jakarta.servlet.DispatcherType;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway 사용자 헤더를 연결하고 contest 외부·내부 경로의 인증 정책을 구성한다.
 * 내부 API는 서비스 간 인증 방식 결정에 따라 서비스 토큰을 요구하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * 공개 대회 조회와 인증된 contest API를 구분하고 ERROR 디스패치를 허용하는 상태 비저장 보안 체인을 구성한다.
     *
     * @param http 서비스의 HTTP 보안 구성기
     * @param objectMapper 공통 보안 오류 응답에 사용할 JSON 매퍼
     * @return Gateway 헤더 인증과 경로 인가를 적용하는 보안 체인
     * @throws Exception 보안 필터 체인을 구성하지 못한 경우
     */
    @Bean
    SecurityFilterChain contestSecurityFilterChain(
        HttpSecurity http,
        ObjectMapper objectMapper,
        Environment environment
    )
        throws Exception {
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
            .exceptionHandling(
                errors -> errors
                    .authenticationEntryPoint(new SecurityAuthenticationEntryPoint(objectMapper))
                    .accessDeniedHandler(new SecurityAccessDeniedHandler(objectMapper))
            )
            .authorizeHttpRequests(authorize -> {
                authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                if (environment.acceptsProfiles(Profiles.of("local"))) {
                    authorize.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                }
                authorize
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/contests",
                        "/api/v1/contests/*",
                        "/api/v1/contests/*/leaderboard"
                    )
                    .permitAll()
                    .requestMatchers("/internal/v1/**")
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

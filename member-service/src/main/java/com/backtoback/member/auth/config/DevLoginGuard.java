package com.backtoback.member.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 운영 환경(prod·staging)에서 개발용 로그인이 켜져 있으면 애플리케이션 기동을 실패시킨다 (API 명세서 1.1.6).
 */
@Configuration(proxyBeanMethods = false)
public class DevLoginGuard {

    public DevLoginGuard(AuthProperties authProperties, Environment environment) {
        if (authProperties.devLoginEnabled() && environment.matchesProfiles("prod", "staging")) {
            throw new IllegalStateException("prod·staging 프로필에서는 개발용 로그인(DEV_LOGIN_ENABLED)을 켤 수 없습니다.");
        }
    }
}

package com.backtoback.gateway.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

/**
 * 게이트웨이 인증 및 보안 설정 프로퍼티.
 */
@ConfigurationProperties(prefix = "resolve.auth")
public class AuthProperties {

    private static final List<String> RESTRICTED_PROFILES = List.of("prod", "staging");

    private final Environment environment;
    private String jwtSecret;
    private boolean bypassEnabled;

    @Autowired
    public AuthProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateSecurityPolicy() {
        if (!bypassEnabled) {
            return;
        }

        String[] activeProfiles = environment.getActiveProfiles();
        boolean hasRestrictedProfile = Arrays.stream(activeProfiles).anyMatch(RESTRICTED_PROFILES::contains);

        if (hasRestrictedProfile) {
            throw new IllegalStateException(
                "인증 우회 설정(resolve.auth.bypass-enabled)은 prod 또는 staging 프로필에서 활성화할 수 없습니다."
            );
        }
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public boolean isBypassEnabled() {
        return bypassEnabled;
    }

    public void setBypassEnabled(boolean bypassEnabled) {
        this.bypassEnabled = bypassEnabled;
    }
}

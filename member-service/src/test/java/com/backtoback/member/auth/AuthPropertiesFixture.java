package com.backtoback.member.auth;

import java.time.Duration;
import java.util.List;

import com.backtoback.member.auth.config.AuthProperties;

public final class AuthPropertiesFixture {

    public static final String FRONTEND_ORIGIN = "https://resolve.test";
    public static final String JWT_SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes";

    private AuthPropertiesFixture() {}

    public static AuthProperties create() {
        return new AuthProperties(
            FRONTEND_ORIGIN,
            List.of("/"),
            new AuthProperties.GitHub(
                "client-id",
                "client-secret",
                "https://api.resolve.test/api/v1/auth/github/callback",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.com"
            ),
            new AuthProperties.Jwt(JWT_SECRET, Duration.ofHours(1)),
            new AuthProperties.RefreshToken(Duration.ofDays(14), Duration.ofSeconds(10), true)
        );
    }
}

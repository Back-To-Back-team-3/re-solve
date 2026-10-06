package com.backtoback.member.auth.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "resolve.auth")
public record AuthProperties(
    @NotBlank String frontendOrigin,
    @NotEmpty List<String> allowedRedirectPaths,
    @Valid @NotNull GitHub github,
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull RefreshToken refreshToken
) {

    public record GitHub(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri,
        @NotBlank String authorizeUri,
        @NotBlank String tokenUri,
        @NotBlank String apiBaseUri
    ) {
    }

    public record Jwt(@NotBlank String secret, @NotNull Duration accessTokenTtl) {

        private static final int MIN_HS256_SECRET_BYTES = 32;

        @AssertTrue(message = "JWT_SECRET은 32바이트 이상이어야 합니다.")
        public boolean isSecretLongEnough() {
            return secret == null || secret.getBytes(StandardCharsets.UTF_8).length >= MIN_HS256_SECRET_BYTES;
        }
    }

    public record RefreshToken(@NotNull Duration ttl, boolean cookieSecure) {
    }
}

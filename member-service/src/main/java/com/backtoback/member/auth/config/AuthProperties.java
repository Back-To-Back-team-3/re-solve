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

    /**
     * @param reuseGrace 교체된 Refresh Token을 재사용으로 보지 않는 유예 시간. 여러 탭·동시 요청이 거의 같은 때
     *     재발급을 시도해도 정상 사용자가 로그아웃되지 않게 한다.
     */
    public record RefreshToken(@NotNull Duration ttl, @NotNull Duration reuseGrace, boolean cookieSecure) {
    }
}

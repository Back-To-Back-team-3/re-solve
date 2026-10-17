package com.backtoback.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtException;

import com.backtoback.gateway.config.AuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class JwtTokenValidatorTest {

    private static final String TEST_SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes";

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private AuthProperties properties;
    private JwtTokenValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties(Mockito.mock(Environment.class));
        properties.setJwtSecret(TEST_SECRET);
        validator = new JwtTokenValidator(properties, redisTemplate);
    }

    @Test
    @DisplayName("유효한 토큰에서 회원 식별자와 역할을 정상적으로 추출한다")
    void validatesValidToken() throws Exception {
        String token = createToken("7", "ADMIN", UUID.randomUUID().toString(), 3600);
        given(redisTemplate.hasKey(Mockito.anyString())).willReturn(Mono.just(false));

        UserPrincipal principal = validator.validate(token).block();

        assertThat(principal).isNotNull();
        assertThat(principal.memberId()).isEqualTo("7");
        assertThat(principal.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("만료된 토큰은 JwtException이 발생한다")
    void throwsExceptionOnExpiredToken() throws Exception {
        String expiredToken = createToken("7", "USER", UUID.randomUUID().toString(), -10);

        assertThatThrownBy(() -> validator.validate(expiredToken).block()).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 서명 키로 서명된 토큰은 JwtException이 발생한다")
    void throwsExceptionOnInvalidSignature() throws Exception {
        String otherSecret = "other-jwt-secret-key-must-be-at-least-32-bytes";
        String invalidToken = createTokenWithSecret("7", "USER", UUID.randomUUID().toString(), 3600, otherSecret);

        assertThatThrownBy(() -> validator.validate(invalidToken).block()).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Redis 차단 목록에 등록된 로그아웃 토큰은 차단된다")
    void blocksTokenWhenInRedisBlocklist() throws Exception {
        String jti = UUID.randomUUID().toString();
        String token = createToken("7", "USER", jti, 3600);
        given(redisTemplate.hasKey(eq(JwtTokenValidator.BLOCKLIST_KEY_PREFIX + jti))).willReturn(Mono.just(true));

        assertThatThrownBy(() -> validator.validate(token).block())
            .isInstanceOf(JwtException.class)
            .hasMessageContaining("로그아웃된 인증 토큰");
    }

    @Test
    @DisplayName("jti(토큰 식별자)가 없는 토큰은 JwtException이 발생한다")
    void throwsExceptionWhenJtiIsMissing() throws Exception {
        String tokenWithoutJti = createToken("7", "USER", null, 3600);

        assertThatThrownBy(() -> validator.validate(tokenWithoutJti).block())
            .isInstanceOf(JwtException.class)
            .hasMessageContaining("식별자(jti)");
    }

    @Test
    @DisplayName("토큰 식별자(jti)가 차단되지 않았더라도 로그인 세션(sid)이 차단 목록에 있으면 차단된다")
    void blocksTokenWhenSessionIsInRedisBlocklist() throws Exception {
        String jti = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        String token = createTokenWithSession("7", "USER", jti, sid, 3600);

        given(redisTemplate.hasKey(eq(JwtTokenValidator.BLOCKLIST_KEY_PREFIX + jti))).willReturn(Mono.just(false));
        given(redisTemplate.hasKey(eq(JwtTokenValidator.SESSION_BLOCKLIST_KEY_PREFIX + sid)))
            .willReturn(Mono.just(true));

        assertThatThrownBy(() -> validator.validate(token).block())
            .isInstanceOf(JwtException.class)
            .hasMessageContaining("로그아웃된 인증 토큰");
    }

    private String createToken(String sub, String role, String jti, long ttlSeconds) throws Exception {
        return createTokenWithSecret(sub, role, jti, ttlSeconds, TEST_SECRET);
    }

    private String createTokenWithSession(String sub, String role, String jti, String sid, long ttlSeconds)
        throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder
            = new JWTClaimsSet.Builder()
                .issuer("re-solve")
                .subject(sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("role", role);

        if (jti != null) {
            builder.jwtID(jti);
        }
        if (sid != null) {
            builder.claim(JwtTokenValidator.SESSION_CLAIM, sid);
        }

        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
        signedJwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }

    private String createTokenWithSecret(String sub, String role, String jti, long ttlSeconds, String secret)
        throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder
            = new JWTClaimsSet.Builder()
                .issuer("re-solve")
                .subject(sub)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("role", role);

        if (jti != null) {
            builder.jwtID(jti);
        }

        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
        signedJwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }
}

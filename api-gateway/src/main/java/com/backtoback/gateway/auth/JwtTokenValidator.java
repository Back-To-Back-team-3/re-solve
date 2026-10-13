package com.backtoback.gateway.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.backtoback.gateway.config.AuthProperties;

import reactor.core.publisher.Mono;

/**
 * JWT 서명 검증, 만료 확인 및 Redis 로그아웃 차단 목록 조회를 수행하는 컴포넌트.
 */
@Component
public class JwtTokenValidator {

    public static final String BLOCKLIST_KEY_PREFIX = "auth:access-token-blocklist:";
    public static final String ROLE_CLAIM = "role";

    private final AuthProperties authProperties;
    private final ReactiveStringRedisTemplate redisTemplate;

    public JwtTokenValidator(
        AuthProperties authProperties,
        @Autowired(required = false) ReactiveStringRedisTemplate redisTemplate
    ) {
        this.authProperties = authProperties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Bearer 토큰을 검증하고 사용자 식별 정보를 반환합니다.
     */
    public Mono<UserPrincipal> validate(String token) {
        return Mono.defer(() -> {
            NimbusReactiveJwtDecoder decoder;
            try {
                decoder = createDecoder();
            } catch (Exception exception) {
                return Mono.error(new JwtException("JWT 검증 디코더를 초기화할 수 없습니다.", exception));
            }
            return decoder.decode(token);
        }).flatMap(jwt -> checkBlocklist(jwt).thenReturn(jwt)).map(this::extractPrincipal);
    }

    private NimbusReactiveJwtDecoder createDecoder() {
        String secret = authProperties.getJwtSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("JWT_SECRET 설정이 비어 있습니다.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private Mono<Void> checkBlocklist(Jwt jwt) {
        String jti = jwt.getId();
        if (!StringUtils.hasText(jti) || redisTemplate == null) {
            return Mono.empty();
        }

        return redisTemplate.hasKey(BLOCKLIST_KEY_PREFIX + jti).flatMap(isBlocked -> {
            if (Boolean.TRUE.equals(isBlocked)) {
                return Mono.error(new JwtException("로그아웃된 인증 토큰입니다."));
            }
            return Mono.empty();
        });
    }

    private UserPrincipal extractPrincipal(Jwt jwt) {
        String memberId = jwt.getSubject();
        if (!StringUtils.hasText(memberId)) {
            throw new JwtException("토큰에 식별자(sub)가 존재하지 않습니다.");
        }
        String role = jwt.getClaimAsString(ROLE_CLAIM);
        if (!StringUtils.hasText(role)) {
            role = "USER";
        }
        return new UserPrincipal(memberId, role);
    }
}

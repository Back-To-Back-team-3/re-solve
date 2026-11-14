package com.backtoback.gateway.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

    public static final String BLOCKLIST_KEY_PREFIX = "auth:access-token-blocklist:";
    public static final String SESSION_BLOCKLIST_KEY_PREFIX = "auth:session-blocklist:";
    public static final String ROLE_CLAIM = "role";
    public static final String SESSION_CLAIM = "sid";

    private final AuthProperties authProperties;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final NimbusReactiveJwtDecoder decoder;

    public JwtTokenValidator(
        AuthProperties authProperties,
        @Autowired(required = false) ReactiveStringRedisTemplate redisTemplate
    ) {
        this.authProperties = authProperties;
        this.redisTemplate = redisTemplate;

        if (StringUtils.hasText(authProperties.getJwtSecret())) {
            this.decoder = createDecoder(authProperties);
        } else {
            this.decoder = null;
            log.warn("JWT_SECRET 설정이 비어 있어 JWT 디코더가 초기화되지 않았습니다.");
        }

        if (redisTemplate == null) {
            log.warn("ReactiveStringRedisTemplate이 구성되지 않아 로그아웃 토큰 차단 목록(Blocklist) 검증이 비활성화됩니다.");
        }
    }

    /**
     * Bearer 토큰을 검증하고 사용자 식별 정보를 반환합니다.
     */
    public Mono<UserPrincipal> validate(String token) {
        if (decoder == null) {
            return Mono.error(new JwtException("JWT_SECRET 설정이 비어 있습니다."));
        }
        return decoder.decode(token).flatMap(jwt -> checkBlocklist(jwt).thenReturn(jwt)).map(this::extractPrincipal);
    }

    private static NimbusReactiveJwtDecoder createDecoder(AuthProperties authProperties) {
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
        if (!StringUtils.hasText(jti)) {
            return Mono.error(new JwtException("토큰에 식별자(jti)가 존재하지 않습니다."));
        }
        if (redisTemplate == null) {
            return Mono.empty();
        }

        String sid = jwt.getClaimAsString(SESSION_CLAIM);
        Mono<Boolean> tokenBlockedMono = redisTemplate.hasKey(BLOCKLIST_KEY_PREFIX + jti);

        if (StringUtils.hasText(sid)) {
            Mono<Boolean> sessionBlockedMono = redisTemplate.hasKey(SESSION_BLOCKLIST_KEY_PREFIX + sid);
            return Mono.zip(tokenBlockedMono, sessionBlockedMono).flatMap(tuple -> {
                boolean isTokenBlocked = Boolean.TRUE.equals(tuple.getT1());
                boolean isSessionBlocked = Boolean.TRUE.equals(tuple.getT2());
                if (isTokenBlocked || isSessionBlocked) {
                    return Mono.error(new JwtException("로그아웃된 인증 토큰입니다."));
                }
                return Mono.empty();
            });
        }

        return tokenBlockedMono.flatMap(isBlocked -> {
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

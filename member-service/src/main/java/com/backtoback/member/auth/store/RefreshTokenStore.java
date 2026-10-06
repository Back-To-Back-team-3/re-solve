package com.backtoback.member.auth.store;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.token.SecureTokenGenerator;

/**
 * Refresh Token을 Redis에 사용자별로 저장한다. 원문 대신 SHA-256 해시를 키로 쓴다.
 * 회원별 해시 목록은 재사용 감지 시 해당 회원의 토큰을 모두 폐기하는 데 쓴다(정책 및 상태 §1).
 */
@Component
public class RefreshTokenStore {

    static final String TOKEN_KEY_PREFIX = "auth:refresh-token:";
    static final String MEMBER_TOKENS_KEY_PREFIX = "auth:member-refresh-tokens:";

    private final StringRedisTemplate redisTemplate;
    private final SecureTokenGenerator tokenGenerator;
    private final Duration ttl;

    public RefreshTokenStore(
        StringRedisTemplate redisTemplate,
        SecureTokenGenerator tokenGenerator,
        AuthProperties authProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.tokenGenerator = tokenGenerator;
        this.ttl = authProperties.refreshToken().ttl();
    }

    public String issue(Long memberId) {
        String refreshToken = tokenGenerator.generate();
        String tokenHash = tokenGenerator.hash(refreshToken);
        String memberTokensKey = MEMBER_TOKENS_KEY_PREFIX + memberId;

        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + tokenHash, String.valueOf(memberId), ttl);
        redisTemplate.opsForSet().add(memberTokensKey, tokenHash);
        redisTemplate.expire(memberTokensKey, ttl);
        return refreshToken;
    }

    public Duration ttl() {
        return ttl;
    }
}

package com.backtoback.member.auth.store;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 로그아웃한 Access Token을 막는 차단 목록. Access Token을 검증하는 API Gateway가 두 키 중 하나라도 있으면 거부한다.
 * <ul>
 * <li>{@code auth:access-token-blocklist:{jti}}: 로그아웃 요청에 쓴 Access Token. 남은 수명 동안</li>
 * <li>{@code auth:session-blocklist:{sid}}: 같은 로그인 세션에서 발급된 다른 Access Token(다른 탭·유예 재발급). Access Token 수명 동안</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccessTokenBlocklist {

    public static final String KEY_PREFIX = "auth:access-token-blocklist:";
    public static final String SESSION_KEY_PREFIX = "auth:session-blocklist:";
    private static final String BLOCKED = "1";

    private final StringRedisTemplate redisTemplate;

    public void block(String tokenId, Duration remainingLifetime) {
        if (remainingLifetime.isNegative() || remainingLifetime.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, BLOCKED, remainingLifetime);
    }

    public void blockSession(String sessionId, Duration accessTokenTtl) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, BLOCKED, accessTokenTtl);
    }

    public boolean isSessionBlocked(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(SESSION_KEY_PREFIX + sessionId));
    }

    public boolean isBlocked(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
    }
}

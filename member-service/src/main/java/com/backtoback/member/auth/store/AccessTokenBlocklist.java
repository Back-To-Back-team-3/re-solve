package com.backtoback.member.auth.store;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 로그아웃한 Access Token을 남은 수명 동안 막는 차단 목록. 키는 {@code auth:access-token-blocklist:{jti}}이며,
 * Access Token을 검증하는 API Gateway가 이 키의 존재 여부로 차단을 판단한다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenBlocklist {

    public static final String KEY_PREFIX = "auth:access-token-blocklist:";
    private static final String BLOCKED = "1";

    private final StringRedisTemplate redisTemplate;

    public void block(String tokenId, Duration remainingLifetime) {
        if (remainingLifetime.isNegative() || remainingLifetime.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, BLOCKED, remainingLifetime);
    }

    public boolean isBlocked(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
    }
}

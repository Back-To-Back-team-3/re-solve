package com.backtoback.member.auth.store;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * GitHub 로그인 시작 때 발급한 OAuth {@code state}와 로그인 후 이동 경로. 콜백에서 한 번만 꺼낼 수 있다.
 */
@Component
@RequiredArgsConstructor
public class OAuthStateStore {

    static final String KEY_PREFIX = "auth:oauth-state:";
    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public void save(String state, String redirectPath) {
        redisTemplate.opsForValue().set(KEY_PREFIX + state, redirectPath, TTL);
    }

    /**
     * state를 꺼내면서 지운다(GETDEL). 없거나 만료됐거나 이미 쓰였으면 빈 값을 돌려준다.
     */
    public Optional<String> consume(String state) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state));
    }
}

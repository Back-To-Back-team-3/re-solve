package com.backtoback.member.auth.store;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * GitHub 로그인 시작 때 발급한 OAuth {@code state}. 로그인 후 이동 경로와 로그인을 시작한 브라우저의 nonce 해시를 함께 저장하고,
 * 콜백에서 한 번만 꺼낼 수 있다.
 */
@Component
@RequiredArgsConstructor
public class OAuthStateStore {

    public static final Duration TTL = Duration.ofMinutes(10);
    static final String KEY_PREFIX = "auth:oauth-state:";
    /**
     * nonce 해시(16진수)와 이동 경로 사이 구분자. 이동 경로는 제어 문자를 허용하지 않으므로 겹치지 않는다.
     */
    private static final String SEPARATOR = "\n";

    private final StringRedisTemplate redisTemplate;

    public void save(String state, String redirectPath, String browserNonceHash) {
        redisTemplate.opsForValue().set(KEY_PREFIX + state, browserNonceHash + SEPARATOR + redirectPath, TTL);
    }

    /**
     * state를 꺼내면서 지운다(GETDEL). 없거나 만료됐거나 이미 쓰였으면 빈 값을 돌려준다.
     */
    public Optional<OAuthState> consume(String state) {
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
        if (value == null || !value.contains(SEPARATOR)) {
            return Optional.empty();
        }
        String[] parts = value.split(SEPARATOR, 2);
        return Optional.of(new OAuthState(parts[1], parts[0]));
    }

    public record OAuthState(String redirectPath, String browserNonceHash) {
    }
}

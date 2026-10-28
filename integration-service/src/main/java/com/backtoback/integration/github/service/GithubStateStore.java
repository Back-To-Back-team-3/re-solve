package com.backtoback.integration.github.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.integration.github.config.GithubProperties;

import lombok.RequiredArgsConstructor;

/**
 * GitHub App 설치 state 저장소 (api-spec.md > 8.1.2).
 * Redis에 state를 키로, 회원 ID를 값으로 보관하며(TTL 10분), 1회용으로 소비(GETDEL)된다.
 */
@Component
@RequiredArgsConstructor
public class GithubStateStore {

    static final String KEY_PREFIX = "integration:github:state:";

    private final StringRedisTemplate redisTemplate;
    private final GithubProperties properties;

    public void save(String state, Long memberId) {
        Duration ttl = Duration.ofSeconds(properties.getStateTtlSeconds());
        redisTemplate.opsForValue().set(KEY_PREFIX + state, String.valueOf(memberId), ttl);
    }

    /**
     * state를 조회하면서 삭제한다(1회용 소비).
     *
     * @param state CSRF 방지용 state 값
     * @return 저장되었던 회원 ID (없거나 만료된 경우 empty)
     */
    public Optional<Long> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}

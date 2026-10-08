package com.backtoback.member.auth.store;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.token.SecureTokenGenerator;

/**
 * Refresh Token을 Redis에 사용자별로 저장하고 재발급 때 교체한다(정책 및 상태 §1). 원문 대신 SHA-256 해시를 키로 쓴다.
 * <ul>
 * <li>{@code auth:refresh-token:{hash}}: 해시 {@code memberId}, {@code status}(ACTIVE·ROTATED), {@code rotatedAt}</li>
 * <li>{@code auth:member-refresh-tokens:{memberId}}: 회원의 토큰 해시 목록. 재사용 감지 시 모두 폐기할 때 쓴다.</li>
 * </ul>
 * 상태 확인과 변경은 Lua 스크립트로 한 번에 처리한다.
 */
@Component
public class RefreshTokenStore {

    static final String TOKEN_KEY_PREFIX = "auth:refresh-token:";
    static final String MEMBER_TOKENS_KEY_PREFIX = "auth:member-refresh-tokens:";
    private static final String RESULT_SEPARATOR = ":";

    private static final RedisScript<Long> ISSUE_SCRIPT = script("redis/refresh-token-issue.lua", Long.class);
    private static final RedisScript<String> ROTATE_SCRIPT = script("redis/refresh-token-rotate.lua", String.class);
    private static final RedisScript<Long> REVOKE_SCRIPT = script("redis/refresh-token-revoke.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final SecureTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration ttl;
    private final Duration reuseGrace;

    public RefreshTokenStore(
        StringRedisTemplate redisTemplate,
        SecureTokenGenerator tokenGenerator,
        AuthProperties authProperties,
        Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.ttl = authProperties.refreshToken().ttl();
        this.reuseGrace = authProperties.refreshToken().reuseGrace();
    }

    public String issue(Long memberId) {
        String refreshToken = tokenGenerator.generate();
        String tokenHash = tokenGenerator.hash(refreshToken);
        redisTemplate
            .execute(
                ISSUE_SCRIPT,
                List.of(TOKEN_KEY_PREFIX + tokenHash, MEMBER_TOKENS_KEY_PREFIX + memberId),
                String.valueOf(memberId),
                String.valueOf(ttl.toMillis()),
                tokenHash
            );
        return refreshToken;
    }

    /**
     * 제출된 Refresh Token을 새 토큰으로 바꾼다.
     * 이미 교체된 토큰이면 유예 시간 안에서는 {@link RotationOutcome#GRACE}, 지난 뒤에는 회원의 토큰을 모두 폐기하고
     * {@link RotationOutcome#REUSED}를 돌려준다.
     */
    public RotationResult rotate(String refreshToken) {
        String newRefreshToken = tokenGenerator.generate();
        String newTokenHash = tokenGenerator.hash(newRefreshToken);
        String result
            = redisTemplate
                .execute(
                    ROTATE_SCRIPT,
                    List.of(TOKEN_KEY_PREFIX + tokenGenerator.hash(refreshToken), TOKEN_KEY_PREFIX + newTokenHash),
                    String.valueOf(clock.millis()),
                    String.valueOf(reuseGrace.toMillis()),
                    String.valueOf(ttl.toMillis()),
                    newTokenHash,
                    MEMBER_TOKENS_KEY_PREFIX,
                    TOKEN_KEY_PREFIX
                );

        if (result == null || !result.contains(RESULT_SEPARATOR)) {
            return new RotationResult(RotationOutcome.INVALID, null, null);
        }
        String[] parts = result.split(RESULT_SEPARATOR, 2);
        RotationOutcome outcome = RotationOutcome.valueOf(parts[0]);
        Long memberId = Long.valueOf(parts[1]);
        return new RotationResult(outcome, memberId, outcome == RotationOutcome.ROTATED ? newRefreshToken : null);
    }

    /**
     * 로그아웃할 때 본인 Refresh Token 하나를 폐기한다. 없거나 다른 회원의 토큰이면 아무것도 하지 않는다.
     */
    public void revoke(Long memberId, String refreshToken) {
        String tokenHash = tokenGenerator.hash(refreshToken);
        redisTemplate
            .execute(
                REVOKE_SCRIPT,
                List.of(TOKEN_KEY_PREFIX + tokenHash, MEMBER_TOKENS_KEY_PREFIX + memberId),
                String.valueOf(memberId),
                tokenHash
            );
    }

    public Duration ttl() {
        return ttl;
    }

    private static <T> RedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    public enum RotationOutcome {
        /**
         * 새 Refresh Token을 발급했다.
         */
        ROTATED,
        /**
         * 방금 교체된 토큰이다. Access Token만 새로 발급하고 쿠키는 바꾸지 않는다.
         */
        GRACE,
        /**
         * 유예가 지난 교체 토큰을 재사용했다. 회원의 Refresh Token을 모두 폐기했다.
         */
        REUSED,
        /**
         * 없거나 만료됐거나 폐기된 토큰이다.
         */
        INVALID
    }

    /**
     * @param newRefreshToken {@link RotationOutcome#ROTATED}일 때만 값이 있다
     */
    public record RotationResult(RotationOutcome outcome, Long memberId, String newRefreshToken) {
    }
}

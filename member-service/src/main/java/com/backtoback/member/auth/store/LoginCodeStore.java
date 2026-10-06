package com.backtoback.member.auth.store;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.member.auth.token.SecureTokenGenerator;

import lombok.RequiredArgsConstructor;

/**
 * GitHub 콜백 뒤 프론트로 넘기는 1회용 로그인 교환 코드. 토큰을 URL에 싣지 않기 위해 쓴다.
 */
@Component
@RequiredArgsConstructor
public class LoginCodeStore {

    static final String KEY_PREFIX = "auth:login-code:";
    static final String CODE_PREFIX = "lc_";
    static final Duration TTL = Duration.ofSeconds(60);
    private static final String SEPARATOR = ":";

    private final StringRedisTemplate redisTemplate;
    private final SecureTokenGenerator tokenGenerator;

    public String issue(Long memberId, boolean newMember) {
        String loginCode = CODE_PREFIX + tokenGenerator.generate();
        redisTemplate.opsForValue().set(KEY_PREFIX + loginCode, memberId + SEPARATOR + newMember, TTL);
        return loginCode;
    }

    /**
     * 코드를 꺼내면서 지운다(GETDEL). 없거나 만료됐거나 이미 쓰였으면 빈 값을 돌려준다.
     */
    public Optional<LoginCodeClaim> consume(String loginCode) {
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + loginCode);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(SEPARATOR);
        return Optional.of(new LoginCodeClaim(Long.valueOf(parts[0]), Boolean.parseBoolean(parts[1])));
    }

    public record LoginCodeClaim(Long memberId, boolean newMember) {
    }
}

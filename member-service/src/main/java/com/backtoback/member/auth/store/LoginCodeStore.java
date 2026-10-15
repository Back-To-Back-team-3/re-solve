package com.backtoback.member.auth.store;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.member.auth.token.SecureTokenGenerator;

import lombok.RequiredArgsConstructor;

/**
 * GitHub 콜백 뒤 프론트로 넘기는 1회용 로그인 교환 코드. 토큰을 URL에 싣지 않기 위해 쓴다.
 * 로그인을 시작한 브라우저의 nonce 해시를 함께 저장해, 다른 브라우저에서는 교환할 수 없게 한다.
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

    public String issue(Long memberId, boolean newMember, String browserNonceHash) {
        String loginCode = CODE_PREFIX + tokenGenerator.generate();
        String value = memberId + SEPARATOR + newMember + SEPARATOR + browserNonceHash;
        redisTemplate.opsForValue().set(KEY_PREFIX + loginCode, value, TTL);
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
        String[] parts = value.split(SEPARATOR, 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        return Optional.of(new LoginCodeClaim(Long.valueOf(parts[0]), Boolean.parseBoolean(parts[1]), parts[2]));
    }

    public record LoginCodeClaim(Long memberId, boolean newMember, String browserNonceHash) {
    }
}

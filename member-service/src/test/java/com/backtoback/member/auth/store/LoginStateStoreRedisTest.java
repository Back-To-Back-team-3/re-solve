package com.backtoback.member.auth.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.backtoback.member.auth.store.LoginCodeStore.LoginCodeClaim;
import com.backtoback.member.auth.store.OAuthStateStore.OAuthState;
import com.backtoback.member.auth.token.SecureTokenGenerator;

class LoginStateStoreRedisTest {

    private StringRedisTemplate redisTemplate;
    private OAuthStateStore oAuthStateStore;
    private LoginCodeStore loginCodeStore;

    @BeforeEach
    void setUp() {
        redisTemplate = RedisTestSupport.redisTemplate();
        RedisTestSupport.flush(redisTemplate);
        oAuthStateStore = new OAuthStateStore(redisTemplate);
        loginCodeStore = new LoginCodeStore(redisTemplate, new SecureTokenGenerator());
    }

    @Test
    @DisplayName("OAuth state는 이동 경로·브라우저 nonce 해시와 함께 10분 저장되고 한 번만 꺼낼 수 있다")
    void oauthStateIsSingleUse() {
        oAuthStateStore.save("state-1", "/studies/12?tab=a&b=c", "hash-1");

        assertThat(redisTemplate.getExpire(OAuthStateStore.KEY_PREFIX + "state-1")).isBetween(590L, 600L);
        assertThat(oAuthStateStore.consume("state-1")).contains(new OAuthState("/studies/12?tab=a&b=c", "hash-1"));
        assertThat(oAuthStateStore.consume("state-1")).isEmpty();
    }

    @Test
    @DisplayName("로그인 교환 코드는 회원·신규 여부·브라우저 nonce 해시와 함께 60초 저장되고 한 번만 꺼낼 수 있다")
    void loginCodeIsSingleUse() {
        String loginCode = loginCodeStore.issue(7L, true, "hash-1");

        assertThat(loginCode).startsWith(LoginCodeStore.CODE_PREFIX);
        assertThat(redisTemplate.getExpire(LoginCodeStore.KEY_PREFIX + loginCode)).isBetween(55L, 60L);
        assertThat(loginCodeStore.consume(loginCode)).contains(new LoginCodeClaim(7L, true, "hash-1"));
        assertThat(loginCodeStore.consume(loginCode)).isEmpty();
    }
}

package com.backtoback.member.auth.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.backtoback.member.auth.AuthPropertiesFixture;
import com.backtoback.member.auth.store.RefreshTokenStore.RotationOutcome;
import com.backtoback.member.auth.store.RefreshTokenStore.RotationResult;
import com.backtoback.member.auth.token.SecureTokenGenerator;

class RefreshTokenStoreRedisTest {

    private static final Long MEMBER_ID = 7L;

    private StringRedisTemplate redisTemplate;
    private MutableClock clock;
    private RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = RedisTestSupport.redisTemplate();
        RedisTestSupport.flush(redisTemplate);
        clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
        store = new RefreshTokenStore(redisTemplate, new SecureTokenGenerator(), AuthPropertiesFixture.create(), clock);
    }

    @Test
    @DisplayName("발급한 토큰은 14일 수명으로 저장되고 원문이 아닌 해시가 키가 된다")
    void issueStoresHashedToken() {
        String refreshToken = store.issue(MEMBER_ID);

        String tokenKey = RefreshTokenStore.TOKEN_KEY_PREFIX + new SecureTokenGenerator().hash(refreshToken);
        assertThat(redisTemplate.<String, String>opsForHash().get(tokenKey, "memberId")).isEqualTo("7");
        assertThat(redisTemplate.<String, String>opsForHash().get(tokenKey, "status")).isEqualTo("ACTIVE");
        assertThat(redisTemplate.getExpire(tokenKey))
            .isBetween(Duration.ofDays(14).minusMinutes(1).toSeconds(), Duration.ofDays(14).toSeconds());
        assertThat(redisTemplate.keys(RefreshTokenStore.TOKEN_KEY_PREFIX + refreshToken)).isEmpty();
    }

    @Test
    @DisplayName("교체하면 새 토큰이 나오고, 새 토큰으로 다시 교체할 수 있다")
    void rotateIssuesNewToken() {
        String first = store.issue(MEMBER_ID);

        RotationResult second = store.rotate(first);
        RotationResult third = store.rotate(second.newRefreshToken());

        assertThat(second.outcome()).isEqualTo(RotationOutcome.ROTATED);
        assertThat(second.memberId()).isEqualTo(MEMBER_ID);
        assertThat(second.newRefreshToken()).isNotEqualTo(first);
        assertThat(third.outcome()).isEqualTo(RotationOutcome.ROTATED);
    }

    @Test
    @DisplayName("방금 교체된 토큰을 유예 시간(10초) 안에 다시 쓰면 GRACE이고 새 토큰을 만들지 않는다")
    void reuseWithinGraceIsAllowed() {
        String first = store.issue(MEMBER_ID);
        RotationResult rotated = store.rotate(first);

        clock.advance(Duration.ofSeconds(10));
        RotationResult again = store.rotate(first);

        assertThat(again.outcome()).isEqualTo(RotationOutcome.GRACE);
        assertThat(again.memberId()).isEqualTo(MEMBER_ID);
        assertThat(again.newRefreshToken()).isNull();
        assertThat(store.rotate(rotated.newRefreshToken()).outcome()).isEqualTo(RotationOutcome.ROTATED);
    }

    @Test
    @DisplayName("유예가 지난 뒤 교체된 토큰을 다시 쓰면 REUSED이고 회원의 모든 토큰이 폐기된다")
    void reuseAfterGraceRevokesAllTokens() {
        String first = store.issue(MEMBER_ID);
        String otherDevice = store.issue(MEMBER_ID);
        RotationResult rotated = store.rotate(first);
        String otherMember = store.issue(8L);

        clock.advance(Duration.ofSeconds(11));
        RotationResult reused = store.rotate(first);

        assertThat(reused.outcome()).isEqualTo(RotationOutcome.REUSED);
        assertThat(reused.memberId()).isEqualTo(MEMBER_ID);
        assertThat(store.rotate(rotated.newRefreshToken()).outcome()).isEqualTo(RotationOutcome.INVALID);
        assertThat(store.rotate(otherDevice).outcome()).isEqualTo(RotationOutcome.INVALID);
        assertThat(store.rotate(first).outcome()).isEqualTo(RotationOutcome.INVALID);
        assertThat(store.rotate(otherMember).outcome()).isEqualTo(RotationOutcome.ROTATED);
    }

    @Test
    @DisplayName("없는 토큰은 INVALID")
    void unknownTokenIsInvalid() {
        assertThat(store.rotate("unknown").outcome()).isEqualTo(RotationOutcome.INVALID);
    }

    @Test
    @DisplayName("같은 토큰으로 동시에 재발급해도 교체는 한 번만 일어나고 나머지는 GRACE")
    void concurrentRotationRotatesOnce() throws Exception {
        String refreshToken = store.issue(MEMBER_ID);
        int requests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RotationResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests; index++) {
                Callable<RotationResult> task = () -> {
                    start.await();
                    return store.rotate(refreshToken);
                };
                futures.add(executor.submit(task));
            }
            start.countDown();

            List<RotationOutcome> outcomes = new ArrayList<>();
            for (Future<RotationResult> future : futures) {
                outcomes.add(future.get().outcome());
            }
            assertThat(outcomes).containsOnly(RotationOutcome.ROTATED, RotationOutcome.GRACE);
            assertThat(outcomes).filteredOn(outcome -> outcome == RotationOutcome.ROTATED).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("로그아웃하면 본인 토큰만 폐기된다")
    void revokeDeletesOwnTokenOnly() {
        String mine = store.issue(MEMBER_ID);
        String other = store.issue(8L);

        store.revoke(MEMBER_ID, mine);
        store.revoke(MEMBER_ID, other);

        assertThat(store.rotate(mine).outcome()).isEqualTo(RotationOutcome.INVALID);
        assertThat(store.rotate(other).outcome()).isEqualTo(RotationOutcome.ROTATED);
        assertThat(redisTemplate.opsForSet().members(RefreshTokenStore.MEMBER_TOKENS_KEY_PREFIX + MEMBER_ID)).isEmpty();
    }

    @Test
    @DisplayName("차단한 Access Token은 남은 수명 동안만 차단 목록에 있다")
    void blocklistExpiresWithAccessToken() {
        AccessTokenBlocklist blocklist = new AccessTokenBlocklist(redisTemplate);

        blocklist.block("jti-1", Duration.ofMinutes(20));
        blocklist.block("jti-expired", Duration.ZERO);

        assertThat(blocklist.isBlocked("jti-1")).isTrue();
        assertThat(redisTemplate.getExpire(AccessTokenBlocklist.KEY_PREFIX + "jti-1")).isBetween(1190L, 1200L);
        assertThat(blocklist.isBlocked("jti-expired")).isFalse();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

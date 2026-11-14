package com.backtoback.judge.run.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.run.store.RunLockStore.Lease;

/** 실제 Redis에서 인스턴스 간 제한과 중단 실행의 회수를 확인한다. */
@Testcontainers(disabledWithoutDocker = true)
class RunLockStoreTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private RunLockStore locks;

    /** 격리한 Redis에 두 인스턴스가 사용할 연결을 준비한다. */
    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    /** 테스트 Redis 연결을 종료한다. */
    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    /** 테스트마다 실행 제한과 인스턴스 레지스트리를 초기화한다. */
    @BeforeEach
    void reset() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        locks = new RunLockStore(redis);
    }

    /** 서로 다른 인스턴스가 같은 회원 제한을 동시에 획득할 수 없는지 확인한다. */
    @Test
    @DisplayName("같은 회원의 동시 요청 중 하나만 획득하고 종료 직후 다시 획득한다")
    void acquiresOnceAcrossInstances() throws Exception {
        var start = new CountDownLatch(1);
        var another = new RunLockStore(redis);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> acquireAfter(start, locks));
            var second = executor.submit(() -> acquireAfter(start, another));
            start.countDown();
            var firstLease = first.get(10, TimeUnit.SECONDS);
            var secondLease = second.get(10, TimeUnit.SECONDS);
            assertThat(firstLease == null).isNotEqualTo(secondLease == null);
            var winner = firstLease == null ? secondLease : firstLease;
            assertThat(redis.getExpire("run:7")).isEqualTo(-1L);
            locks.release(winner);
            var next = another.acquire(7);
            locks.release(winner);
            assertThat(locks.heartbeat(winner)).isFalse();
            assertThat(redis.opsForValue().get("run:7")).isEqualTo(next.value());
        }
    }

    /** 한 인스턴스에서 다른 실행이 살아 있어도 중단된 토큰만 회수한다. */
    @Test
    @DisplayName("공유 레지스트리가 갱신돼도 만료 토큰만 회수한다")
    void expiresEachTokenIndependently() {
        var stopped = age(locks.acquire(7));
        var active = locks.acquire(8);
        assertThat(locks.heartbeat(active)).isTrue();
        assertThat(locks.reconcile()).isEqualTo(1);
        assertThat(redis.hasKey("run:7")).isFalse();
        assertThat(redis.opsForValue().get("run:8")).isEqualTo(active.value());
        assertThat(locks.heartbeat(stopped)).isFalse();
        assertThat(locks.acquire(7).token()).isNotEqualTo(stopped.token());
    }

    /** 레지스트리 키가 사라진 중단 실행도 회수한다. */
    @Test
    @DisplayName("시작 후 40초가 지난 실행의 레지스트리가 없으면 회수한다")
    void recoversMissingRegistry() {
        var stopped = age(locks.acquire(7));
        redis.delete("run:active:" + stopped.instanceId());
        assertThat(locks.reconcile()).isEqualTo(1);
        assertThat(locks.acquire(7).token()).isNotEqualTo(stopped.token());
    }

    /** 관측한 후보 뒤에 다른 소유자가 생기면 기존 후보의 삭제를 건너뛴다. */
    @Test
    @DisplayName("중단 후보를 읽은 뒤 새 실행이 시작돼도 새 제한을 지우지 않는다")
    void preservesReacquiredLock() {
        var observed = age(locks.acquire(7));
        locks.release(observed);
        var current = locks.acquire(7);
        assertThat(locks.recover(observed)).isFalse();
        assertThat(redis.opsForValue().get("run:7")).isEqualTo(current.value());
        assertThatThrownBy(() -> locks.acquire(7))
            .isInstanceOfSatisfying(
                JudgeException.class,
                failure -> assertThat(failure.getErrorCode().getCode()).isEqualTo("RUN_RATE_LIMITED")
            );
    }

    /** 같은 시작 신호에서 경쟁시키고 제한 거절을 다른 실패와 구분한다. */
    private Lease acquireAfter(CountDownLatch start, RunLockStore candidate) throws InterruptedException {
        start.await();
        try {
            return candidate.acquire(7);
        } catch (JudgeException failure) {
            assertThat(failure.getErrorCode().getCode()).isEqualTo("RUN_RATE_LIMITED");
            return null;
        }
    }

    /** 실제 대기 없이 서버 시작값과 토큰의 마지막 갱신값을 과거로 이동한다. */
    private Lease age(Lease lease) {
        long started = lease.startedAt() - 60000;
        String value = lease.token() + "|" + lease.instanceId() + "|" + started;
        redis.opsForValue().set("run:" + lease.memberId(), value);
        redis.opsForZSet().add("run:active:" + lease.instanceId(), lease.token(), started);
        return new Lease(lease.memberId(), lease.token(), lease.instanceId(), value, started);
    }
}

package com.backtoback.problem.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * 실제 Redis에서 잠금 해제와 소유자 보호를 확인한다.
 * DB 트랜잭션 콜백은 직접 호출하며 연결 장애는 소유권 조회에만 주입한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisTransactionLockIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Mock
    private RedissonClient client;

    private RedissonClient owner;
    private RedissonClient competitor;

    @BeforeAll
    static void startRedis() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 Redis 통합 테스트를 건너뜁니다.");
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        owner = createClient();
        competitor = createClient();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clear();
        if (competitor != null) {
            competitor.shutdown();
        }
        if (owner != null) {
            owner.shutdown();
        }
    }

    @Test
    @DisplayName("종료 전 소유권 조회에 오류를 주입해도 실제 Redis 잠금을 해제하고 다른 클라이언트가 획득한다")
    void ownershipQueryFailureDoesNotLeaveRedisLockAfterCommit() {
        String key = "transaction-lock-test:" + UUID.randomUUID();
        RLock lock = spy(owner.getLock(key));
        given(client.getLock(key)).willReturn(lock);
        AtomicInteger ownershipQueries = new AtomicInteger();
        // 첫 검사는 실제 Redis로 수행하고 이후 조회만 실패시켜 해제 전 재조회 경로를 재현한다.
        willAnswer(invocation -> {
            if (ownershipQueries.getAndIncrement() == 0) {
                return invocation.callRealMethod();
            }
            throw new RedisConnectionException("Redis temporarily unavailable");
        }).given(lock).isHeldByCurrentThread();
        var transactionLock = new RedisTransactionLock(client);
        assertThat(transactionLock.acquire(key)).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.beforeCommit(false);

        assertThatCode(() -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED))
            .doesNotThrowAnyException();
        verify(lock, times(1)).isHeldByCurrentThread();
        assertThat(owner.getKeys().countExists(key)).isZero();

        RLock nextLock = competitor.getLock(key);
        assertThat(nextLock.tryLock()).isTrue();
        nextLock.unlock();
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("기존 잠금이 유실돼도 종료 처리가 실패하지 않고 새 소유자의 잠금은 유지한다")
    void lostLockDoesNotReleaseAnotherClientsLock(boolean acquiredByCompetitor) {
        String key = "transaction-lock-test:" + UUID.randomUUID();
        var transactionLock = new RedisTransactionLock(owner);
        assertThat(transactionLock.acquire(key)).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();

        // TTL 대기 대신 기존 잠금을 제거해 만료·유실 이후의 소유권 상태를 만든다.
        assertThat(owner.getLock(key).forceUnlock()).isTrue();
        RLock nextLock = competitor.getLock(key);
        if (acquiredByCompetitor) {
            assertThat(nextLock.tryLock()).isTrue();
        }

        assertThatThrownBy(() -> callback.beforeCommit(false)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
            .doesNotThrowAnyException();

        if (acquiredByCompetitor) {
            assertThat(nextLock.isHeldByCurrentThread()).isTrue();
            assertThat(nextLock.getHoldCount()).isEqualTo(1);
        } else {
            assertThat(owner.getKeys().countExists(key)).isZero();
            assertThat(nextLock.tryLock()).isTrue();
        }
        nextLock.unlock();
    }

    private RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }
}

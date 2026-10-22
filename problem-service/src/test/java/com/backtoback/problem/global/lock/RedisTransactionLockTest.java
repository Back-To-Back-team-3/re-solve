package com.backtoback.problem.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 콜백의 잠금 획득·소유권 검사·해제 실패 처리를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisTransactionLockTest {
    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    @InjectMocks
    private RedisTransactionLock transactionLock;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    @Test
    @DisplayName("활성 트랜잭션이 없으면 Redis 잠금을 요청하지 않는다")
    void requiresActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> transactionLock.acquire("test-lock")).isInstanceOf(IllegalStateException.class);
        verify(redisson, never()).getLock("test-lock");
    }

    @Test
    @DisplayName("잠금 획득에 실패하면 해제 콜백을 등록하지 않는다")
    void busyLockDoesNotRegisterReleaseCallback() throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(false);
        assertThat(transactionLock.acquire("test-lock")).isFalse();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Redis 오류가 발생하면 잠금 획득을 중단한다")
    void redisFailureStopsAcquisition() throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willThrow(new RedisConnectionException("Redis unavailable"));
        assertThatThrownBy(() -> transactionLock.acquire("test-lock")).isInstanceOf(RedisConnectionException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("잠금 대기 중 인터럽트가 발생하면 상태를 보존하고 중단한다")
    void interruptIsPreservedAndStopsAcquisition() throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willThrow(new InterruptedException());
        assertThatThrownBy(() -> transactionLock.acquire("test-lock")).isInstanceOf(IllegalStateException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @DisplayName("소유권을 잃으면 커밋을 막고 비소유자의 해제 오류는 전파하지 않는다")
    void lostLockPreventsCommitAndReleaseFailureDoesNotEscape() throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);
        given(lock.isHeldByCurrentThread()).willReturn(false);
        willThrow(new IllegalMonitorStateException("Lock belongs to another owner")).given(lock).unlock();
        assertThat(transactionLock.acquire("test-lock")).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatThrownBy(() -> callback.beforeCommit(false)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
            .doesNotThrowAnyException();
        verify(lock).unlock();
    }

    @ParameterizedTest
    @ValueSource(
        ints = {
            TransactionSynchronization.STATUS_COMMITTED,
            TransactionSynchronization.STATUS_ROLLED_BACK
        }
    )
    @DisplayName("커밋 전 검사 이후 소유권 조회가 실패할 상황에도 트랜잭션 종료 시 해제한다")
    void ownershipQueryFailureAfterCommitCheckDoesNotSkipRelease(int status) throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);
        // 커밋 전 검사는 성공시키고 이후 조회만 실패시켜 해제 전 재조회 여부를 확인한다.
        given(lock.isHeldByCurrentThread())
            .willReturn(true)
            .willThrow(new RedisConnectionException("Redis temporarily unavailable"));
        assertThat(transactionLock.acquire("test-lock")).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.beforeCommit(false);
        verify(lock, never()).unlock();

        assertThatCode(() -> callback.afterCompletion(status)).doesNotThrowAnyException();
        verify(lock).unlock();
        verify(lock, times(1)).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("커밋 전 소유권 조회가 실패해도 롤백 종료 시 잠금 해제를 시도한다")
    void ownershipQueryFailureBeforeCommitStillAttemptsReleaseOnRollback() throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);
        given(lock.isHeldByCurrentThread()).willThrow(new RedisConnectionException("Redis temporarily unavailable"));
        assertThat(transactionLock.acquire("test-lock")).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatThrownBy(() -> callback.beforeCommit(false)).isInstanceOf(RedisConnectionException.class);
        assertThatCode(() -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
            .doesNotThrowAnyException();
        verify(lock).unlock();
    }

    @ParameterizedTest
    @ValueSource(
        ints = {
            TransactionSynchronization.STATUS_COMMITTED,
            TransactionSynchronization.STATUS_ROLLED_BACK
        }
    )
    @DisplayName("잠금 해제의 Redis 오류가 이미 종료한 트랜잭션 결과를 바꾸지 않는다")
    void releaseFailureDoesNotReplaceCompletedResult(int status) throws Exception {
        given(redisson.getLock("test-lock")).willReturn(lock);
        given(lock.tryLock(3, TimeUnit.SECONDS)).willReturn(true);
        willThrow(new RedisConnectionException("Redis unavailable")).given(lock).unlock();
        assertThat(transactionLock.acquire("test-lock")).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            given(lock.isHeldByCurrentThread()).willReturn(true);
            callback.beforeCommit(false);
        }
        verify(lock, never()).unlock();

        assertThatCode(() -> callback.afterCompletion(status)).doesNotThrowAnyException();
        verify(lock).unlock();
    }
}

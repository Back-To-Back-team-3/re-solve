package com.backtoback.problem.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RedisTransactionLockTests {
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final RedisTransactionLock transactionLock = new RedisTransactionLock(redisson);

    /**
     * 잠금 모의 객체와 현재 스레드의 트랜잭션 동기화를 준비한다.
     */
    @BeforeEach
    void setUp() {
        when(redisson.getLock("test-lock")).thenReturn(lock);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    /**
     * 테스트가 남긴 트랜잭션 동기화와 인터럽트 상태를 정리한다.
     */
    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    /**
     * 활성 트랜잭션이 없으면 Redis 잠금을 요청하지 않는지 검증한다.
     */
    @Test
    void requiresActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> transactionLock.acquire("test-lock")).isInstanceOf(IllegalStateException.class);
        verify(redisson, never()).getLock("test-lock");
    }

    /**
     * 잠금 획득 실패 시 해제 콜백을 등록하지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void busyLockDoesNotRegisterReleaseCallback() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);
        assertThat(transactionLock.acquire("test-lock")).isFalse();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(lock, never()).unlock();
    }

    /**
     * Redis 오류 발생 시 잠금 획득 실패를 호출자에게 전달하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void redisFailureStopsAcquisition() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThatThrownBy(() -> transactionLock.acquire("test-lock")).hasMessage("Redis unavailable");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    /**
     * 잠금 대기 중 인터럽트를 보존하고 처리를 중단하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void interruptIsPreservedAndStopsAcquisition() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        assertThatThrownBy(() -> transactionLock.acquire("test-lock")).isInstanceOf(IllegalStateException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /**
     * 잠금을 잃으면 커밋을 막고 다른 소유자의 잠금을 해제하지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void lostLockPreventsCommitAndDoesNotReleaseAnotherOwnersLock() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        assertThat(transactionLock.acquire("test-lock")).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        assertThatThrownBy(() -> callback.beforeCommit(false)).isInstanceOf(IllegalStateException.class);
        callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(lock, never()).unlock();
    }

    /**
     * 잠금 해제 오류가 이미 커밋된 DB 처리 결과를 바꾸지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void releaseFailureDoesNotReplaceCommittedResult() throws Exception {
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalStateException("Redis unavailable")).when(lock).unlock();
        assertThat(transactionLock.acquire("test-lock")).isTrue();
        var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.beforeCommit(false);
        verify(lock, never()).unlock();
        assertThatCode(() -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED))
            .doesNotThrowAnyException();
    }
}

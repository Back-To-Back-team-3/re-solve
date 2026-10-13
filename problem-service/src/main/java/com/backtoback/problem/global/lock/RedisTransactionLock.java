package com.backtoback.problem.global.lock;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisTransactionLock implements TransactionLock {
    private static final Logger LOG = LoggerFactory.getLogger(RedisTransactionLock.class);
    private static final long WAIT_SECONDS = 3;

    private final RedissonClient redisson;

    /**
     * {@inheritDoc}
     * 최대 3초 동안 잠금을 기다리며, Redisson의 자동 연장으로 잠금을 유지한다.
     * 커밋 직전 잠금 소유권 유실이 확인되면 예외를 발생시켜 롤백한다.
     */
    @Override
    public boolean acquire(String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Redis transaction lock requires an active transaction");
        }
        RLock lock = redisson.getLock(key);
        try {
            if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Redis lock", exception);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 커밋 전에 현재 스레드의 Redis 잠금 소유권을 확인한다.
             *
             * @param readOnly 현재 트랜잭션의 읽기 전용 여부
             * @throws IllegalStateException 현재 스레드가 잠금을 보유하지 않은 경우
             */
            @Override
            public void beforeCommit(boolean readOnly) {
                // 잠금 유실이나 Redis 장애가 확인되면 DB 변경을 커밋하지 않는다.
                if (!lock.isHeldByCurrentThread()) {
                    throw new IllegalStateException("Redis lock was lost before commit");
                }
            }

            /**
             * 트랜잭션 종료 후 현재 스레드가 보유한 Redis 잠금을 해제한다.
             * 잠금 해제 실패는 로그로 남기고 이미 끝난 DB 트랜잭션의 결과를 유지한다.
             *
             * @param status 트랜잭션 완료 상태
             */
            @Override
            public void afterCompletion(int status) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException exception) {
                    // 이미 끝난 DB 작업의 결과는 유지하고 해제 실패는 로그로 남긴다.
                    LOG.error("Failed to release Redis transaction lock: key={}", key, exception);
                }
            }
        });
        return true;
    }
}

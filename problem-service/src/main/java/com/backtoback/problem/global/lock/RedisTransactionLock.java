package com.backtoback.problem.global.lock;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

import lombok.RequiredArgsConstructor;

/**
 * Redis 잠금으로 DB 트랜잭션의 경합을 줄이고 커밋 직전에 소유권을 확인한다.
 * 확인 이후 실제 DB 커밋까지의 독점은 보장하지 않으며 최종 정합성은 DB 잠금·제약·버전 검사에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class RedisTransactionLock implements TransactionLock {
    private static final Logger LOG = LoggerFactory.getLogger(RedisTransactionLock.class);
    private static final long WAIT_SECONDS = 3;

    private final RedissonClient redisson;

    /**
     * {@inheritDoc}
     * 최대 3초 동안 잠금을 기다리며 Redisson의 자동 연장을 사용한다.
     * 커밋 직전 소유권 유실이 확인되면 버전 충돌 예외를 발생시켜 롤백하고, 종료 후 해제를 시도한다.
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
             * @throws ProblemException 현재 스레드가 잠금을 잃어 {@code PROBLEM_VERSION_CONFLICT}가 발생한 경우
             */
            @Override
            public void beforeCommit(boolean readOnly) {
                // 명시적으로 소유권을 잃은 경우만 동시 변경 충돌로 응답하고, Redis 조회 장애는 그대로 전파한다.
                if (!lock.isHeldByCurrentThread()) {
                    throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
                }
            }

            /**
             * 트랜잭션 종료 후 현재 스레드가 보유한 Redis 잠금을 해제한다.
             * 잠금 해제 실패는 로그로 남기고 이미 끝난 DB 트랜잭션의 결과를 유지한다.
             */
            @Override
            public void afterCompletion(int status) {
                try {
                    // 별도 소유권 조회가 실패해도 해제 시도와 Redisson의 자동 연장 취소를 건너뛰지 않는다.
                    // Redisson의 unlock 내부에서 소유자를 검사하므로 다른 소유자의 잠금은 해제하지 않는다.
                    lock.unlock();
                } catch (RuntimeException exception) {
                    // 이미 끝난 DB 작업의 결과는 유지하고 해제 실패는 로그로 남긴다.
                    LOG.error("Failed to release Redis transaction lock: key={}", key, exception);
                }
            }
        });
        return true;
    }
}

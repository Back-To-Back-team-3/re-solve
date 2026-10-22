package com.backtoback.problem.global.lock;

/** 활성 DB 트랜잭션에서 획득한 잠금을 트랜잭션 종료까지 유지하고, 종료 후 해제하는 계약을 정의한다. */
public interface TransactionLock {
    /**
     * 지정한 키의 잠금을 획득하고 현재 DB 트랜잭션이 끝날 때까지 유지한다.
     * 활성 트랜잭션에서 호출해야 하며, 커밋 또는 롤백 후 잠금을 해제한다.
     *
     * @param key 잠금 대상을 구분하는 키
     * @return 잠금 획득에 성공하면 {@code true}, 대기 시간이 초과되면 {@code false}
     * @throws IllegalStateException 활성 트랜잭션이 없거나 대기 중 인터럽트가 발생한 경우
     */
    boolean acquire(String key);
}

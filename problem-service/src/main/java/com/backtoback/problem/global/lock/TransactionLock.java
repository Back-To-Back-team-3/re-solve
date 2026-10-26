package com.backtoback.problem.global.lock;

/** 활성 DB 트랜잭션에서 잠금 소유권을 커밋 직전에 확인하고, 종료 후 해제를 시도하는 계약을 정의한다. */
public interface TransactionLock {
    /**
     * 지정한 키의 잠금을 획득하고 트랜잭션 동기화를 등록한다.
     * 활성 트랜잭션에서 호출해야 하며, 구현체는 커밋 직전에 소유권을 확인하고 종료 후 해제를 시도한다.
     * 커밋 직전 확인과 실제 DB 커밋 사이의 잠금 유실 가능성까지 제거하지는 않는다.
     *
     * @param key 잠금 대상을 구분하는 키
     * @return 잠금 획득에 성공하면 {@code true}, 대기 시간이 초과되면 {@code false}
     * @throws IllegalStateException 활성 트랜잭션이 없거나 대기 중 인터럽트가 발생한 경우
     */
    boolean acquire(String key);
}

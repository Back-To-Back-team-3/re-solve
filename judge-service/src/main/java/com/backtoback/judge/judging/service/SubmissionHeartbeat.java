package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.time.Instant;

import com.backtoback.judge.judging.service.dto.SubmissionLease;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;

/** 한 작업의 모든 테스트에서 공유하는 임대 갱신 주기를 유지한다. 단일 실행 스레드에서 사용한다. */
public final class SubmissionHeartbeat {
    private final SubmissionLease lease;
    private final SubmissionExecutionStore store;
    private final Clock clock;
    private Instant nextRenewal;

    /**
     * 선점 직후 작업 전체의 첫 갱신 시점을 정한다.
     *
     * @param lease 선점 소유권
     * @param store 짧은 트랜잭션으로 소유권을 확인하는 저장소
     * @param clock 공통 시각
     */
    public SubmissionHeartbeat(SubmissionLease lease, SubmissionExecutionStore store, Clock clock) {
        this.lease = lease;
        this.store = store;
        this.clock = clock;
        nextRenewal = clock.instant().plusSeconds(30);
    }

    /**
     * 갱신 시각이면 임대를 갱신하고, 그 외에는 현재 소유권과 처리 기한을 확인한다.
     * 테스트가 바뀌어도 다음 갱신 시각을 초기화하지 않는다.
     *
     * @return 현재 실행을 계속할 수 있는지 여부
     */
    public boolean active() {
        if (clock.instant().isBefore(nextRenewal)) {
            return store.active(lease);
        }
        if (!store.heartbeat(lease)) {
            return false;
        }
        nextRenewal = clock.instant().plusSeconds(30);
        return true;
    }

    /**
     * 테스트 결과 저장에 사용할 작업 소유권을 반환한다.
     *
     * @return 작업 소유권
     */
    public SubmissionLease lease() {
        return lease;
    }
}

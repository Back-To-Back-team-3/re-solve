package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.service.dto.SubmissionReconciliationCandidate;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 상태 대사의 실패 격리와 메모리 순회 위치를 확인한다. */
@ExtendWith(MockitoExtension.class)
class JudgeReconciliationSchedulerTest {
    @Mock
    private SubmissionExecutionStore store;
    @Mock
    private SubmissionJudgingDataClient dataClient;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** 테스트별 관측 지표를 정리한다. */
    @AfterEach
    void closeMeters() {
        meters.close();
    }

    /** 작업 누락·자료 조회 실패 뒤에도 계속 확인하고 끝에서는 처음으로 돌아간다. */
    @Test
    @DisplayName("실패 후보도 지나가고 조회 끝에 도달하면 다음 회차는 처음부터 확인한다")
    void advancesPastFailuresAndWrapsAtEnd() {
        var missing = new SubmissionReconciliationCandidate(1, null, 1, "QUEUED", null, 1, 1);
        var failed = new SubmissionReconciliationCandidate(2, 2L, 1, "JUDGING", "SUCCEEDED", 1, 1);
        var waiting = new SubmissionReconciliationCandidate(3, 3L, 1, "JUDGING", "PENDING", 1, 1);
        given(store.reconciliationCandidates(0)).willReturn(List.of(missing, failed));
        given(store.reconciliationCandidates(2)).willReturn(List.of(waiting));
        given(store.reconciliationCandidates(3)).willReturn(List.of());
        given(dataClient.findForReconciliation(1, 1)).willThrow(new IllegalStateException("metadata unavailable"));
        var scheduler = new JudgeReconciliationScheduler(store, dataClient, meters);

        scheduler.reconcile();
        scheduler.reconcile();
        scheduler.reconcile();
        scheduler.reconcile();

        var order = inOrder(store);
        order.verify(store).reconciliationCandidates(0);
        order.verify(store).missingReconciliationJob(missing);
        order.verify(store).reconciliationCandidates(2);
        order.verify(store).reconcile(waiting, null);
        order.verify(store).reconciliationCandidates(3);
        order.verify(store).reconciliationCandidates(0);
        verify(store, org.mockito.Mockito.times(2)).missingReconciliationJob(missing);
        assertThat(
            meters.get("scheduler.run.duration").tag("scheduler", "JudgeReconciliationScheduler").timer().count()
        ).isEqualTo(4);
        assertThat(meters.get("scheduler.items").tag("result", "failed").counter().count()).isEqualTo(2);
        assertThat(meters.get("scheduler.items").tag("result", "skipped").counter().count()).isEqualTo(3);
    }

    /** 보정·누락 결과와 커서에 영향받지 않는 적체 조회를 공통 지표에 연결한다. */
    @Test
    @DisplayName("대사 보정·누락 건수와 적체 경과 시간을 작업명 태그로 기록한다")
    void recordsCorrectedMissingAndBacklog() {
        var missing = new SubmissionReconciliationCandidate(1, null, 1, "QUEUED", null, 1, 1);
        var corrected = new SubmissionReconciliationCandidate(2, 2L, 1, "JUDGING", "PENDING", 1, 1);
        given(store.reconciliationCandidates(0)).willReturn(List.of(missing, corrected));
        given(store.missingReconciliationJob(missing)).willReturn(true);
        given(store.reconcile(corrected, null)).willReturn(true);
        given(store.reconciliationBacklogAgeSeconds()).willReturn(180.0);
        var scheduler = new JudgeReconciliationScheduler(store, dataClient, meters);
        scheduler.registerBacklogGauge();

        scheduler.reconcile();

        assertThat(meters.get("scheduler.items").tag("result", "corrected").counter().count()).isEqualTo(1);
        assertThat(meters.get("scheduler.items").tag("result", "missing").counter().count()).isEqualTo(1);
        assertThat(meters.get("judge.reconciliation.missing.jobs").counter().count()).isEqualTo(1);
        assertThat(
            meters
                .get("scheduler.backlog.oldest.seconds")
                .tag("scheduler", "JudgeReconciliationScheduler")
                .gauge()
                .value()
        ).isEqualTo(180);
    }

}

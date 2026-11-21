package com.backtoback.study.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Limit;

import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 추천 갱신 스케줄러의 청크 순회·실패 격리·실행 시간 상한·지표를 검증한다(스케줄러 명세 5.4·0.1).
 */
@ExtendWith(MockitoExtension.class)
class RecommendationRefreshSchedulerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-11-20T18:00:00Z");
    private static final Limit CHUNK = Limit.of(RecommendationRefreshScheduler.CHUNK_SIZE);
    private static final String JOB = "RecommendationRefreshScheduler";

    @Mock
    private StudyRecommendationRefreshService refreshService;

    @Mock
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    @Mock
    private Clock clock;

    private SimpleMeterRegistry meterRegistry;

    private RecommendationRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler
            = new RecommendationRefreshScheduler(
                refreshService,
                learningProfileReplicaRepository,
                clock,
                meterRegistry
            );
        scheduler.initMetrics();
    }

    @Test
    @DisplayName("청크를 회원 ID 순서대로 이어서 조회하고 빈 청크가 나오면 끝낸다")
    void refreshAll_iteratesChunksUntilEmpty() {
        // given
        given(clock.instant()).willReturn(STARTED_AT);
        given(learningProfileReplicaRepository.findMemberIdsAfter(0L, CHUNK)).willReturn(List.of(3L, 5L));
        given(learningProfileReplicaRepository.findMemberIdsAfter(5L, CHUNK)).willReturn(List.of(9L));
        given(learningProfileReplicaRepository.findMemberIdsAfter(9L, CHUNK)).willReturn(List.of());

        // when
        scheduler.refreshAll();

        // then
        InOrder order = inOrder(refreshService);
        order.verify(refreshService).refresh(3L);
        order.verify(refreshService).refresh(5L);
        order.verify(refreshService).refresh(9L);
        assertThat(counter("refreshed")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("한 회원의 재계산이 실패해도 다음 회원을 계속 처리하고 실패 수를 남긴다")
    void refreshAll_oneMemberFails_continuesWithOthers() {
        // given
        given(clock.instant()).willReturn(STARTED_AT);
        given(learningProfileReplicaRepository.findMemberIdsAfter(0L, CHUNK)).willReturn(List.of(1L, 2L));
        given(learningProfileReplicaRepository.findMemberIdsAfter(2L, CHUNK)).willReturn(List.of());
        willThrow(new PessimisticLockingFailureException("deadlock")).given(refreshService).refresh(1L);

        // when
        scheduler.refreshAll();

        // then
        then(refreshService).should().refresh(2L);
        assertThat(counter("failed")).isEqualTo(1.0);
        assertThat(counter("refreshed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("실행 시간이 50분에 이르면 남은 회원을 처리하지 않고 회차를 끝낸다")
    void refreshAll_reachesTimeLimit_stopsRemainingMembers() {
        // given
        given(clock.instant())
            .willReturn(STARTED_AT, STARTED_AT, STARTED_AT.plus(RecommendationRefreshScheduler.MAX_RUN_DURATION));
        given(learningProfileReplicaRepository.findMemberIdsAfter(0L, CHUNK)).willReturn(List.of(1L, 2L, 3L));

        // when
        scheduler.refreshAll();

        // then
        then(refreshService).should().refresh(1L);
        then(refreshService).should(never()).refresh(2L);
        then(refreshService).should(never()).refresh(3L);
        then(learningProfileReplicaRepository).should(never()).findMemberIdsAfter(1L, CHUNK);
    }

    @Test
    @DisplayName("대상 회원이 없어도 실행 시간 지표를 작업명 태그와 함께 기록한다")
    void refreshAll_noMembers_recordsRunDuration() {
        // given
        given(clock.instant()).willReturn(STARTED_AT, STARTED_AT.plus(Duration.ofSeconds(2)));
        given(learningProfileReplicaRepository.findMemberIdsAfter(0L, CHUNK)).willReturn(List.of());

        // when
        scheduler.refreshAll();

        // then
        then(refreshService).should(never()).refresh(anyLong());
        assertThat(meterRegistry.get("scheduler.run.duration").tag("job", JOB).timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("scheduler.run.duration").tag("job", JOB).timer().totalTime(TimeUnit.SECONDS))
            .isEqualTo(2.0);
    }

    private double counter(String result) {
        return meterRegistry.get("scheduler.items").tag("job", JOB).tag("result", result).counter().count();
    }
}

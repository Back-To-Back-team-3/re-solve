package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.judge.judging.store.ValidationExecutionStore;

/** 실행 슬롯 상한과 대기열 백오프, 독립적인 기한 초과 처리를 확인한다. */
class ValidationSchedulerTest {
    private final ValidationWorker worker = mock(ValidationWorker.class);
    private final ValidationExecutionStore store = mock(ValidationExecutionStore.class);
    private final Clock clock = mock(Clock.class);
    private final ArrayList<Runnable> tasks = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));
    private ValidationScheduler scheduler;

    /** 실제 대기 없이 슬롯 실행 시각을 제어한다. */
    @BeforeEach
    void prepare() {
        given(clock.instant()).willAnswer(call -> now.get());
        scheduler = new ValidationScheduler(worker, store, tasks::add, clock);
    }

    /** 두 슬롯이 진행 중이면 새 실행을 쌓지 않으며 기한 초과 종결은 계속한다. */
    @Test
    @DisplayName("동시 실행은 두 슬롯으로 제한하고 기한 초과 종결은 별도로 호출한다")
    void boundsConcurrency() {
        scheduler.dispatch();
        scheduler.dispatch();
        assertThat(tasks).hasSize(2);
        scheduler.expire();
        verify(store).expireOne();
        tasks.getFirst().run();
        now.set(now.get().plusSeconds(1));
        scheduler.dispatch();
        assertThat(tasks).hasSize(3);
    }

    /** 대기열이 비면 각 슬롯의 재조회 간격을 단계적으로 늘린다. */
    @Test
    @DisplayName("빈 대기열은 1초·2초·5초 뒤 재조회한다")
    void backsOffEmptyQueue() {
        scheduler.dispatch();
        runPending();
        now.set(now.get().plusSeconds(1));
        scheduler.dispatch();
        runPending();
        now.set(now.get().plusSeconds(1));
        scheduler.dispatch();
        assertThat(tasks).isEmpty();
        now.set(now.get().plusSeconds(1));
        scheduler.dispatch();
        runPending();
        now.set(now.get().plusSeconds(4));
        scheduler.dispatch();
        assertThat(tasks).isEmpty();
        now.set(now.get().plusSeconds(1));
        scheduler.dispatch();
        assertThat(tasks).hasSize(2);
        verify(worker, times(6)).runOnce();
    }

    /** 실행 실패 뒤에도 슬롯이 영구 점유되지 않는다. */
    @Test
    @DisplayName("실행 예외 이후 슬롯을 반환하고 다음 회차를 수행한다")
    void recoversFailedSlot() {
        given(worker.runOnce()).willThrow(new IllegalStateException("private-data"));
        scheduler.dispatch();
        runPending();
        now.set(now.get().plusSeconds(1));
        scheduler.dispatch();
        assertThat(tasks).hasSize(2);
    }

    /** 기한 초과 처리 장애는 다음 주기의 실행을 막지 않는다. */
    @Test
    @DisplayName("기한 초과 처리 장애 이후 다음 주기를 다시 수행한다")
    void retriesExpiry() {
        given(store.expireOne()).willThrow(new IllegalStateException("offline")).willReturn(true, false);
        scheduler.expire();
        scheduler.expire();
        verify(store, times(3)).expireOne();
    }

    /** 대기 중인 실행들을 현재 시각으로 완료한다. */
    private void runPending() {
        var pending = new ArrayList<>(tasks);
        tasks.clear();
        pending.forEach(Runnable::run);
    }
}

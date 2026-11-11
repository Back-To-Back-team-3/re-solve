package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.backtoback.judge.judging.store.ValidationExecutionStore;
import com.zaxxer.hikari.HikariDataSource;

/** 실행 슬롯 상한과 대기열 백오프, 독립적인 기한 초과 처리를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ValidationSchedulerTest {
    @Mock
    private ValidationWorker worker;
    @Mock
    private ValidationExecutionStore store;
    @Mock
    private Clock clock;
    @Mock
    private JpaTransactionManager transactionManager;
    @Mock
    private HikariDataSource dataSource;
    @Mock
    private Connection connection;
    private final ArrayList<Runnable> tasks = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));
    private ValidationScheduler scheduler;

    /** 실제 대기 없이 슬롯 실행 시각을 제어한다. */
    @BeforeEach
    void prepare() {
        scheduler = new ValidationScheduler(worker, store, tasks::add, clock, transactionManager);
    }

    /** 회수 시나리오에 필요한 연결 풀과 트랜잭션의 JDBC 자원을 준비한다. */
    private void prepareRecovery() {
        given(transactionManager.getDataSource()).willReturn(dataSource);
        given(dataSource.getConnectionTimeout()).willReturn(2000L);
        given(dataSource.getValidationTimeout()).willReturn(1000L);
        given(transactionManager.getTransaction(any(TransactionDefinition.class))).willAnswer(call -> {
            TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
            var holder = new ConnectionHolder(connection);
            holder.setTimeoutInSeconds(12);
            TransactionSynchronizationManager.bindResource(dataSource, holder);
            return new SimpleTransactionStatus();
        });
    }

    /** 테스트 스레드에 연결한 JDBC 자원을 해제한다. */
    @AfterEach
    void clearConnection() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
    }

    /** 두 슬롯이 진행 중이면 새 실행을 쌓지 않으며 기한 초과 종결은 계속한다. */
    @Test
    @DisplayName("동시 실행은 두 슬롯으로 제한하고 기한 초과 종결은 별도로 호출한다")
    void boundsConcurrency() {
        given(clock.instant()).willAnswer(call -> now.get());
        prepareRecovery();
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
        given(clock.instant()).willAnswer(call -> now.get());
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
        given(clock.instant()).willAnswer(call -> now.get());
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
        prepareRecovery();
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

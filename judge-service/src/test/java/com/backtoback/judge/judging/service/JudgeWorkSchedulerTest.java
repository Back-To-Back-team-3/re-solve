package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.zaxxer.hikari.HikariDataSource;

/** 회수 회차의 실행 예산과 건별 트랜잭션을 검증한다. */
@ExtendWith(MockitoExtension.class)
class JudgeWorkSchedulerTest {
    @Mock
    private JpaTransactionManager transactionManager;
    @Mock
    private BooleanSupplier recoverOne;

    @Mock
    private HikariDataSource dataSource;

    /** 운영과 같은 연결 풀 대기 상한을 설정한다. */
    @BeforeEach
    void configurePool() {
        given(transactionManager.getDataSource()).willReturn(dataSource);
        given(dataSource.getConnectionTimeout()).willReturn(2000L);
    }

    /** 테스트 스레드에 연결한 JDBC 자원을 해제한다. */
    @AfterEach
    void clearConnection() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
    }

    /** 남은 예산을 각 트랜잭션에 적용하고 연결 대기까지 확보할 수 없으면 새 회수를 시작하지 않는지 확인한다. */
    @Test
    @DisplayName("연결 대기와 쿼리 시간을 확보할 수 없으면 다음 회수 트랜잭션을 시작하지 않는다")
    void limitsRecoveryByRemainingBudget() {
        var elapsed = new AtomicLong();
        given(transactionManager.getTransaction(any(TransactionDefinition.class))).willAnswer(call -> {
            bindConnection();
            return new SimpleTransactionStatus();
        });
        given(recoverOne.getAsBoolean()).willAnswer(call -> {
            elapsed.set(Duration.ofSeconds(9).toNanos());
            return true;
        }).willAnswer(call -> {
            elapsed.set(Duration.ofMillis(9100).toNanos());
            return true;
        });
        var scheduler
            = new JudgeWorkScheduler(
                () -> false,
                recoverOne,
                Runnable::run,
                Clock.systemUTC(),
                transactionManager,
                elapsed::get
            );

        scheduler.expire();

        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(TransactionDefinition::getTimeout).containsExactly(12, 3);
        assertThat(definitions.getAllValues())
            .extracting(TransactionDefinition::getPropagationBehavior)
            .containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
        verify(recoverOne, times(2)).getAsBoolean();
    }

    /** 트랜잭션을 여는 중 실행 예산을 소진하면 회수 저장소를 호출하지 않는지 확인한다. */
    @Test
    @DisplayName("DB 연결 대기 중 실행 예산이 끝나면 회수 작업을 시작하지 않는다")
    void skipsRecoveryWhenOpeningTransactionExhaustsBudget() {
        var elapsed = new AtomicLong();
        given(transactionManager.getTransaction(any(TransactionDefinition.class))).willAnswer(call -> {
            elapsed.set(Duration.ofSeconds(12).toNanos());
            return new SimpleTransactionStatus();
        });
        var scheduler
            = new JudgeWorkScheduler(
                () -> false,
                recoverOne,
                Runnable::run,
                Clock.systemUTC(),
                transactionManager,
                elapsed::get
            );

        scheduler.expire();

        verifyNoInteractions(recoverOne);
    }

    /** 연결 대기 후에도 남은 회차 예산을 JDBC 제한시간에 적용한다. */
    @Test
    @DisplayName("연결 확보 후 회수 쿼리는 남은 예산만 사용한다")
    void limitsQueryAfterWaitingForConnection() {
        var elapsed = new AtomicLong();
        given(transactionManager.getTransaction(any(TransactionDefinition.class))).willAnswer(call -> {
            elapsed.set(Duration.ofMillis(1750).toNanos());
            bindConnection();
            return new SimpleTransactionStatus();
        });
        given(recoverOne.getAsBoolean()).willAnswer(call -> {
            var connection = (ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);
            assertThat(connection.getTimeToLiveInSeconds()).isEqualTo(10);
            return false;
        });
        var scheduler
            = new JudgeWorkScheduler(
                () -> false,
                recoverOne,
                Runnable::run,
                Clock.systemUTC(),
                transactionManager,
                elapsed::get
            );

        scheduler.expire();

        verify(recoverOne).getAsBoolean();
    }

    /** JDBC 제한시간이 설정된 트랜잭션 연결을 준비한다. */
    private void bindConnection() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        var connection = new ConnectionHolder(mock(java.sql.Connection.class));
        connection.setTimeoutInSeconds(12);
        TransactionSynchronizationManager.bindResource(dataSource, connection);
    }
}

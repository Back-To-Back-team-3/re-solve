package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 회수 회차의 실행 예산과 건별 트랜잭션을 검증한다. */
@ExtendWith(MockitoExtension.class)
class JudgeWorkSchedulerTest {
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private BooleanSupplier recoverOne;

    /** 남은 예산을 각 트랜잭션에 적용하고 1초 미만이면 새 회수를 시작하지 않는지 확인한다. */
    @Test
    @DisplayName("회수 건마다 남은 예산을 적용하고 1초 미만이면 다음 건을 시작하지 않는다")
    void limitsRecoveryByRemainingBudget() {
        var elapsed = new AtomicLong();
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .willAnswer(call -> new SimpleTransactionStatus());
        given(recoverOne.getAsBoolean()).willAnswer(call -> {
            elapsed.set(Duration.ofSeconds(10).toNanos());
            return true;
        }).willAnswer(call -> {
            elapsed.set(Duration.ofMillis(11500).toNanos());
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
        assertThat(definitions.getAllValues()).extracting(TransactionDefinition::getTimeout).containsExactly(12, 2);
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
}

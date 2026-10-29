package com.backtoback.common.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.backtoback.common.event.store.JdbcEventCleanupStore;

/**
 * 실제 대기 없이 단조 시간 공급자를 제어해 잠금 유지시간보다 짧은 실행 예산을 검증한다.
 * SQL 삭제와 실제 커밋·롤백 경계는 MySQL 통합 테스트에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class EventRecordCleanupServiceTest {
    @Mock
    private JdbcEventCleanupStore cleanupStore;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final AtomicLong elapsedNanos = new AtomicLong();
    private EventRecordCleanupService service;

    /**
     * 보관기간 시각은 고정하고 실행 경과 시간은 테스트 본문에서 별도로 조절한다.
     */
    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00.123456789Z"), ZoneOffset.UTC);
        service = new EventRecordCleanupService(cleanupStore, clock, transactionManager, elapsedNanos::get);
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .willAnswer(invocation -> new SimpleTransactionStatus());
    }

    /**
     * 30분 잠금의 절반인 정상 실행 예산을 소진하면 가득 찬 배치 뒤에도 추가 삭제를 시작하지 않는다.
     */
    @Test
    @DisplayName("15분 실행 예산을 소진하면 커밋한 건수만 반환하고 다음 배치는 시작하지 않는다")
    void stopsBeforeStartingBatchAfterBudgetExhaustion() {
        // given
        var cutoff = LocalDateTime.parse("2026-10-23T09:00:00.123456");
        given(cleanupStore.deletePublishedBefore(cutoff)).willAnswer(invocation -> {
            elapsedNanos.set(Duration.ofMinutes(15).toNanos());
            return 1000;
        });

        // when
        var deleted = service.cleanPublishedEvents();

        // then
        assertThat(deleted).isEqualTo(1000);
        verify(cleanupStore).deletePublishedBefore(cutoff);
        verifyNoMoreInteractions(cleanupStore);
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getTimeout()).isEqualTo(900);
        assertThat(definition.getValue().getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager).commit(any());
    }

    /**
     * 다음 배치의 트랜잭션 제한시간이 남은 실행 예산보다 길게 재설정되지 않는지 확인한다.
     */
    @Test
    @DisplayName("후속 배치의 트랜잭션 제한시간은 남은 실행 예산으로 줄어든다")
    void appliesRemainingBudgetToNextTransaction() {
        // given
        var cutoff = LocalDateTime.parse("2026-10-16T09:00:00.123456");
        given(cleanupStore.deleteProcessedBefore(cutoff)).willAnswer(invocation -> {
            if (elapsedNanos.get() == 0) {
                elapsedNanos.set(Duration.ofMinutes(15).minusSeconds(2).toNanos());
                return 1000;
            }
            return 0;
        });

        // when
        var deleted = service.cleanProcessedEvents();

        // then
        assertThat(deleted).isEqualTo(1000);
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(TransactionDefinition::getTimeout).containsExactly(900, 2);
        verify(transactionManager, times(2)).commit(any());
    }
}

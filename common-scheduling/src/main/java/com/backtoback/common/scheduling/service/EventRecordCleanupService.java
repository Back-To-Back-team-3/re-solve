package com.backtoback.common.scheduling.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.store.JdbcEventCleanupStore;
import com.backtoback.common.time.ClockConfiguration;

/**
 * 고정한 보관 경계를 기준으로 이벤트 기록을 1,000건씩 독립 트랜잭션에서 정리한다.
 * 중간 배치가 실패해도 앞선 삭제는 유지하고 다음 실행이 DB 조건으로 남은 기록을 다시 선정한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.1 공통 규칙: 실행시간 상한과 잠금 유지시간의 2배 기준</li>
 * <li>스케줄러 v1.0 / §0.2.2 OutboxCleanupScheduler·§0.2.3 ProcessedEventCleanupScheduler: 보관기간·배치 반복</li>
 * </ul>
 */
public class EventRecordCleanupService {
    private static final Logger LOG = LoggerFactory.getLogger(EventRecordCleanupService.class);
    // 30분 잠금이 정상 최장 실행시간의 2배 이상이 되도록 실행 예산을 절반으로 둔다.
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(30).dividedBy(2);

    private final JdbcEventCleanupStore cleanupStore;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final LongSupplier nanoTime;

    /**
     * 보관기간에는 서비스 Clock을 사용하고 실행 예산에는 시스템 시간 보정에 영향받지 않는 단조 시간을 사용한다.
     *
     * @param cleanupStore 서비스 스키마의 이벤트 기록 저장소
     * @param clock 보관기간 경계를 계산할 서비스 Clock
     * @param transactionManager 서비스 업무 DB의 트랜잭션 관리자
     */
    public EventRecordCleanupService(
        JdbcEventCleanupStore cleanupStore,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this(cleanupStore, clock, transactionManager, System::nanoTime);
    }

    /**
     * 실제 대기 없이 실행 예산 경계를 검증할 수 있도록 단조 시간 공급자를 연결한다.
     *
     * @param cleanupStore 서비스 스키마의 이벤트 기록 저장소
     * @param clock 보관기간 경계를 계산할 서비스 Clock
     * @param transactionManager 서비스 업무 DB의 트랜잭션 관리자
     * @param nanoTime 실행 시작 이후 경과를 측정할 나노초 공급자
     */
    EventRecordCleanupService(
        JdbcEventCleanupStore cleanupStore,
        Clock clock,
        PlatformTransactionManager transactionManager,
        LongSupplier nanoTime
    ) {
        this.cleanupStore = cleanupStore;
        this.clock = clock;
        this.transactionManager = transactionManager;
        this.nanoTime = nanoTime;
    }

    /**
     * 실행 시작 시점의 7일 전을 고정 경계로 사용해 발행 완료 기록만 정리한다.
     *
     * @return 이번 실행에서 커밋한 삭제 행 수
     * @throws RuntimeException 배치 삭제나 커밋이 실패할 때
     */
    public long cleanPublishedEvents() {
        var cutoff = cutoff(7);
        return cleanBatches("outbox_events", () -> cleanupStore.deletePublishedBefore(cutoff));
    }

    /**
     * 실행 시작 시점의 14일 전을 고정 경계로 사용해 소비 완료 기록만 정리한다.
     *
     * @return 이번 실행에서 커밋한 삭제 행 수
     * @throws RuntimeException 배치 삭제나 커밋이 실패할 때
     */
    public long cleanProcessedEvents() {
        var cutoff = cutoff(14);
        return cleanBatches("processed_events", () -> cleanupStore.deleteProcessedBefore(cutoff));
    }

    /**
     * 배치마다 새 트랜잭션을 열고 남은 실행 예산을 JDBC 트랜잭션 제한시간에도 반영한다.
     * 예산을 소진하면 새 배치를 시작하지 않으며 다음 예약 실행이 남은 대상을 처리한다.
     *
     * @param target 처리 결과 로그에 남길 공통 테이블 이름
     * @param deleteBatch 한 번에 최대 1,000건을 삭제할 저장소 호출
     * @return 정상 커밋을 확인한 전체 삭제 행 수
     */
    private long cleanBatches(String target, IntSupplier deleteBatch) {
        var startedAt = nanoTime.getAsLong();
        var totalDeleted = 0L;
        while (true) {
            var remaining = MAX_RUN_DURATION.toNanos() - (nanoTime.getAsLong() - startedAt);
            if (remaining <= 0) {
                LOG.info("Event cleanup budget exhausted: target={}, deleted={}", target, totalDeleted);
                return totalDeleted;
            }
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setTimeout((int)Math.max(1, Duration.ofNanos(remaining).toSeconds()));
            var deleted = transaction.execute(status -> deleteBatch.getAsInt());
            if (deleted == null) {
                throw new IllegalStateException("Cleanup transaction did not return its deletion count");
            }
            totalDeleted += deleted;
            if (deleted < 1000) {
                LOG.info("Event cleanup completed: target={}, deleted={}", target, totalDeleted);
                return totalDeleted;
            }
        }
    }

    /**
     * 애플리케이션 Clock의 현재 시각에서 보관기간을 빼고 DB 정밀도에 맞춘 서울 시간으로 변환한다.
     *
     * @param retentionDays 보관할 일수
     * @return 이 시각보다 이전인 기록만 삭제할 경계
     */
    private LocalDateTime cutoff(int retentionDays) {
        return LocalDateTime
            .ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE)
            .minusDays(retentionDays)
            .truncatedTo(ChronoUnit.MICROS);
    }
}

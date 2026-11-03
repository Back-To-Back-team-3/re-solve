package com.backtoback.study.assignment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.study.assignment.repository.AssignmentRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 문제집 OPEN·CLOSE 상태를 자동 전이하는 스케줄러(스케줄러 명세서 §5.2).
 * ShedLock으로 다중 인스턴스 중복 실행을 방지하고, 건당 독립 트랜잭션으로 1건 실패가 회차 전체를 롤백하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AssignmentLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(AssignmentLifecycleScheduler.class);
    private static final int BATCH_SIZE = 100;
    private static final long CUTOFF_MILLIS = 20_000L;
    private static final String CORRELATION_PREFIX = "sched-AssignmentLifecycleScheduler-";

    private final AssignmentLifecycleService lifecycleService;
    private final AssignmentRepository assignmentRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    private Counter openedCounter;
    private Counter failedCounter;

    @PostConstruct
    void initMetrics() {
        this.openedCounter = Counter.builder("scheduler.items").tag("result", "opened").register(meterRegistry);
        this.failedCounter = Counter.builder("scheduler.items").tag("result", "failed").register(meterRegistry);
    }

    @Scheduled(
        fixedDelay = 60_000,
        initialDelay = 30_000
    )
    @SchedulerLock(
        name = "AssignmentLifecycleScheduler",
        lockAtMostFor = "PT50S"
    )
    public void runBatch() {
        long cutoffAt = clock.instant().toEpochMilli() + CUTOFF_MILLIS;
        LocalDateTime now = LocalDateTime.now(clock);

        List<Long> openIds = assignmentRepository.findScheduledToOpenIds(now, BATCH_SIZE);
        for (Long id : openIds) {
            if (clock.instant().toEpochMilli() > cutoffAt) {
                log.warn("OPEN 전이 실행 시간 초과. 나머지 건은 다음 회차에 처리됩니다.");
                break;
            }
            String correlationId = CORRELATION_PREFIX + UUID.randomUUID();
            try {
                lifecycleService.openTransition(id, correlationId);
                openedCounter.increment();
            } catch (Exception e) {
                log.error("OPEN 전이 실패. assignmentId={}, correlationId={}", id, correlationId, e);
                failedCounter.increment();
            }
        }
    }
}

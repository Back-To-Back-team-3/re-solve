package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.study.membership.repository.StudyMembershipRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 만료된 PENDING 신청을 EXPIRED로 전이하는 스케줄러(스케줄러 명세서 §5.1).
 * ShedLock으로 다중 인스턴스 중복 실행을 방지하고, 건당 독립 트랜잭션으로 1건 실패가 회차 전체를 롤백하지 않는다.
 */
@Component
public class StudyApplicationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(StudyApplicationExpiryScheduler.class);
    private static final int BATCH_SIZE = 500;
    private static final String CORRELATION_PREFIX = "sched-StudyApplicationExpiryScheduler-";

    private final StudyMembershipExpiryService expiryService;
    private final StudyMembershipRepository membershipRepository;
    private final Clock clock;
    private final Counter expiredCounter;
    private final Counter failedCounter;

    /**
     * Micrometer Counter를 생성자에서 등록한다.
     *
     * @param expiryService 만료 1건 처리 서비스
     * @param membershipRepository 대상 ID 조회 리포지토리
     * @param clock 현재 시각 공급자
     * @param meterRegistry 메트릭 레지스트리
     */
    public StudyApplicationExpiryScheduler(
        StudyMembershipExpiryService expiryService,
        StudyMembershipRepository membershipRepository,
        Clock clock,
        MeterRegistry meterRegistry
    ) {
        this.expiryService = expiryService;
        this.membershipRepository = membershipRepository;
        this.clock = clock;
        this.expiredCounter = Counter.builder("scheduler.items").tag("result", "expired").register(meterRegistry);
        this.failedCounter = Counter.builder("scheduler.items").tag("result", "failed").register(meterRegistry);
    }

    /**
     * 1시간마다 만료된 PENDING 신청을 최대 500건 처리한다.
     * 각 건은 독립 트랜잭션으로 처리하므로 예외가 발생해도 나머지 건은 계속 처리한다.
     */
    @Scheduled(
        fixedDelay = 3_600_000,
        initialDelay = 60_000
    )
    @SchedulerLock(
        name = "StudyApplicationExpiryScheduler",
        lockAtMostFor = "PT10M"
    )
    public void expireBatch() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> ids = membershipRepository.findExpiredPendingIds(now, BATCH_SIZE);

        for (Long id : ids) {
            String correlationId = CORRELATION_PREFIX + UUID.randomUUID();
            try {
                expiryService.expire(id, correlationId);
                expiredCounter.increment();
            } catch (Exception e) {
                log.error("PENDING 만료 처리 실패. membershipId={}, correlationId={}", id, correlationId, e);
                failedCounter.increment();
            }
        }
    }
}

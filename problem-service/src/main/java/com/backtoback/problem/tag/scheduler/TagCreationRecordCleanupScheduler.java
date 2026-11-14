package com.backtoback.problem.tag.scheduler;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.problem.tag.service.TagCreationRecordService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "problem.tag-idempotency.cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class TagCreationRecordCleanupScheduler {
    private static final Duration MAX_RUN_DURATION = Duration.ofSeconds(20);
    private static final Logger LOG = LoggerFactory.getLogger(TagCreationRecordCleanupScheduler.class);
    private final TagCreationRecordService creationRecords;
    private final Clock clock;

    /**
     * 매시 정각 만료된 태그 생성 요청을 건별 트랜잭션으로 정리한다.
     * ShedLock 잠금이 유지되는 동안 같은 작업의 다른 인스턴스 실행은 건너뛴다.
     */
    @Scheduled(
        cron = "0 0 * * * *",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(name = "TagCreationRecordCleanupScheduler")
    public void deleteExpiredRecords() {
        // 잠금 프록시를 거치지 않은 호출은 실행하지 않는다.
        LockAssert.assertLocked();
        Instant lastObservedAt = clock.instant();
        Duration elapsed = Duration.ZERO;
        List<Long> candidates = creationRecords.findExpiredIdsForCleanup();
        int deleted = 0;
        int failed = 0;
        for (Long id : candidates) {
            Instant observedAt = clock.instant();
            Duration forwardTime = Duration.between(lastObservedAt, observedAt);
            if (!forwardTime.isNegative()) {
                elapsed = elapsed.plus(forwardTime);
            }
            lastObservedAt = observedAt;
            if (elapsed.compareTo(MAX_RUN_DURATION) >= 0) {
                LOG.info("태그 생성 기록 정리가 20초 처리 한도에 도달했습니다: deleted={}, failed={}", deleted, failed);
                break;
            }
            try {
                if (creationRecords.deleteExpiredById(id)) {
                    deleted++;
                }
            } catch (RuntimeException failure) {
                if (!(failure instanceof DataAccessException)) {
                    if (isConnectionFailure(failure)) {
                        LOG.error("태그 생성 기록 정리를 DB 연결 장애로 중단합니다: id={}", id, failure);
                    }
                    throw failure;
                }
                failed++;
                if (isConnectionFailure(failure)) {
                    LOG.error("태그 생성 기록 정리를 DB 연결 장애로 중단합니다: id={}", id, failure);
                    throw failure;
                }
                LOG.warn("태그 생성 기록 하나의 정리에 실패해 다음 후보를 처리합니다: id={}", id, failure);
            }
        }
        if (deleted > 0 || failed > 0) {
            LOG.info("만료된 태그 생성 기록 정리를 마쳤습니다: deleted={}, failed={}", deleted, failed);
        }
    }

    private boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (cause instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            if (cause.getClass().getSimpleName().equals("JDBCConnectionException")) {
                return true;
            }
        }
        return false;
    }
}

package com.backtoback.problem.tag.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private static final Logger LOG = LoggerFactory.getLogger(TagCreationRecordCleanupScheduler.class);
    private final TagCreationRecordService creationRecords;

    /**
     * 만료된 태그 생성 요청을 한 배치 정리하고 삭제 건수를 기록한다.
     * ShedLock 잠금이 유지되는 동안 같은 작업의 다른 인스턴스 실행은 건너뛴다.
     */
    @Scheduled(
        initialDelayString = "${problem.tag-idempotency.cleanup.interval:PT1H}",
        fixedDelayString = "${problem.tag-idempotency.cleanup.interval:PT1H}"
    )
    @SchedulerLock(name = "TagCreationRecordCleanupScheduler")
    public void deleteExpiredRecords() {
        // 잠금 프록시를 거치지 않은 호출은 실행하지 않는다.
        LockAssert.assertLocked();
        int deleted = creationRecords.deleteExpired();
        if (deleted > 0) {
            LOG.info("만료된 태그 생성 기록을 삭제했습니다: count={}", deleted);
        }
    }
}

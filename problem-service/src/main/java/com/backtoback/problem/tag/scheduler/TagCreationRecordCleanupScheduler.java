package com.backtoback.problem.tag.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
     */
    @Scheduled(
        initialDelayString = "${problem.tag-idempotency.cleanup.interval:PT1H}",
        fixedDelayString = "${problem.tag-idempotency.cleanup.interval:PT1H}"
    )
    public void deleteExpiredRecords() {
        int deleted = creationRecords.deleteExpired();
        if (deleted > 0) {
            LOG.info("Expired tag creation records deleted: count={}", deleted);
        }
    }
}

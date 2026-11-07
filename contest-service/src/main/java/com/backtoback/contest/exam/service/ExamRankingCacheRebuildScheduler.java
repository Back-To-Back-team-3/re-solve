package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.config.ExamRankingCacheProperties;
import com.backtoback.contest.exam.store.ExamRankingCache;

import lombok.RequiredArgsConstructor;

/** 시험 결과 캐시를 DB의 현재 버전으로 주기적으로 복구한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ExamRankingCacheRebuildScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExamRankingCacheRebuildScheduler.class);
    private final JdbcTemplate jdbc;
    private final ExamResultQueryService results;
    private final ExamRankingCache cache;
    private final AtomicLong cursor = new AtomicLong();
    private final Clock clock;
    private final ExamRankingCacheProperties properties;

    /** ID 순으로 한 바퀴를 돌며 20초가 지나면 다음 회차에 이어간다. */
    @Scheduled(
        fixedDelay = 300000,
        initialDelay = 300000
    )
    @SchedulerLock(
        name = "ContestCacheRebuildScheduler",
        lockAtMostFor = "PT30S"
    )
    public void run() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var candidates
                = jdbc
                    .queryForList(
                        "SELECT id FROM exams WHERE id>? AND status IN ('CLOSED','FINALIZED') ORDER BY id LIMIT 100",
                        Long.class,
                        cursor.get()
                    );
            if (candidates.isEmpty()) {
                cursor.set(0);
                return;
            }
            for (long examId : candidates) {
                if (System.nanoTime() >= deadline) {
                    return;
                }
                try {
                    var target
                        = jdbc
                            .queryForObject(
                                "SELECT result_revision,status,finalized_at FROM exams WHERE id=?",
                                (rs, row) -> new Target(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getObject(3, LocalDateTime.class)
                                ),
                                examId
                            );
                    var cutoff
                        = LocalDateTime
                            .ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE)
                            .minusDays(properties.finalizedRetentionDays());
                    if ("FINALIZED".equals(target.status()) && target.finalizedAt() != null
                        && !target.finalizedAt().isAfter(cutoff)) {
                        cache.evict(examId, target.revision());
                        continue;
                    }
                    var snapshot = results.cacheSnapshot(examId);
                    Long current
                        = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, examId);
                    if (snapshot != null && current != null && current == snapshot.revision()) {
                        cache.replace(examId, snapshot.revision(), snapshot.items());
                    }
                } catch (RuntimeException failure) {
                    log.warn("시험 순위 캐시 복구 실패: examId={}, type={}", examId, failure.getClass().getSimpleName());
                } finally {
                    cursor.set(examId);
                }
            }
        }
    }

    /** 캐시 보관 여부를 판별하는 시험 상태와 결과 버전. */
    private record Target(long revision, String status, LocalDateTime finalizedAt) {
    }
}

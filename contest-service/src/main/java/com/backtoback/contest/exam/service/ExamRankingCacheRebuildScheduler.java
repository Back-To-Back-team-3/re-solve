package com.backtoback.contest.exam.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

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
                    var snapshot = results.cacheSnapshot(examId);
                    Long current
                        = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, examId);
                    if (snapshot != null && current != null && current == snapshot.revision()) {
                        cache.replace(examId, snapshot.revision(), snapshot.items());
                    }
                } catch (RuntimeException failure) {
                    log.warn("시험 순위 캐시 복구 실패: examId={}, type={}", examId, failure.getClass().getSimpleName());
                }
                cursor.set(examId);
            }
        }
    }
}

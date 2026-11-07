package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.config.ExamRankingCacheProperties;

/** 확정 시험의 캐시 보관 만료가 DB 결과 조회를 막지 않는지 검증한다. */
class ExamRankingCacheRebuildSchedulerIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("보관 기간이 지난 확정 시험은 캐시를 재생성하지 않고 DB 결과를 보존한다")
    void expiredFinalizedExamRemainsReadableWithoutPeriodicRebuild() {
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        jdbc.update("UPDATE exams SET finalized_at=? WHERE id=?", now.minusDays(7), examId);
        long exam = Long.parseLong(examId);
        long revision = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, exam);
        rankingCache.replace(exam, revision, List.of());
        // TTL 도입 전에 만들어진 무기한 캐시도 정기 순회가 제거해야 한다.
        redis.persist("exam:ranking:{" + exam + "}:current");
        redis.persist("exam:ranking:{" + exam + "}:v:" + revision);
        var query = mock(ExamResultQueryService.class);
        var scheduler
            = new ExamRankingCacheRebuildScheduler(
                jdbc,
                query,
                rankingCache,
                Clock.fixed(now.atZone(ClockConfiguration.SERVICE_ZONE).toInstant(), ClockConfiguration.SERVICE_ZONE),
                new ExamRankingCacheProperties(3600, 7)
            );

        scheduler.run();

        verifyNoInteractions(query);
        assertThat(rankingCache.read(exam, revision)).isNull();
        assertThat(results.cacheSnapshot(exam).revision()).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, exam))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("확정 시험은 보관 기간 안에서 DB 현재 버전의 캐시를 재생성한다")
    void recentFinalizedExamRebuildsCurrentRevision() {
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        long exam = Long.parseLong(examId);
        long revision = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, exam);
        rankingCache.evict(exam, revision);
        var scheduler
            = new ExamRankingCacheRebuildScheduler(
                jdbc,
                results,
                rankingCache,
                Clock.systemUTC(),
                new ExamRankingCacheProperties(3600, 7)
            );

        scheduler.run();

        assertThat(rankingCache.read(exam, revision)).isEmpty();
    }
}

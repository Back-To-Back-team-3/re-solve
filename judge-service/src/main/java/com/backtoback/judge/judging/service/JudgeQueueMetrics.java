package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeJob;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/** 현재 DB 상태를 읽어 관측하며 채점 업무나 실행 시도 수를 변경하지 않는다. */
@Component
public class JudgeQueueMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 관측 전용 쿼리의 실행시간을 제한한다.
     *
     * @param dataSource Judge 업무 DB 연결 공급자
     * @param clock 공통 서비스 시각
     */
    public JudgeQueueMetrics(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(2);
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("judge.jobs.pending.count", this, metrics -> metrics.count("PENDING")).register(registry);
        Gauge
            .builder("scheduler.backlog.oldest.seconds", this, JudgeQueueMetrics::oldestPendingSeconds)
            .tag("scheduler", "JudgeBacklogMonitorScheduler")
            .register(registry);
        for (var status : JudgeJob.Status.values()) {
            Gauge
                .builder("judge.jobs.count", this, metrics -> metrics.count(status.name()))
                .tag("status", status.name())
                .register(registry);
        }
        // 종단 작업은 같은 행을 재조회해도 누적값이 늘지 않는다. 인스턴스별 중복 수집은 max로 집계한다.
        FunctionCounter.builder("judge.jobs.failed", this, metrics -> metrics.count("FAILED")).register(registry);
    }

    /**
     * 현재 상태의 작업 수를 조회한다.
     *
     * @param status 고정 작업 상태
     * @return 해당 상태의 전체 작업 수
     */
    private double count(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM judge_jobs WHERE status=?", Long.class, status);
    }

    /**
     * 가장 오래된 PENDING 작업의 대기 시간을 조회한다.
     *
     * @return 대기 작업이 없으면 0, 있으면 음수가 아닌 대기 초
     */
    private double oldestPendingSeconds() {
        var oldest
            = jdbc.queryForObject("SELECT MIN(created_at) FROM judge_jobs WHERE status='PENDING'", LocalDateTime.class);
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis() / 1000.0);
    }
}

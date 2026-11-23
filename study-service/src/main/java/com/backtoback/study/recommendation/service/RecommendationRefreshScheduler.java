package com.backtoback.study.recommendation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 학습 프로필 복제본이 있는 전체 회원의 추천 결과를 매일 다시 계산하는 스케줄러(스케줄러 명세 5.4, 정책 및 상태 §8.2 > 갱신).
 * ShedLock으로 다중 인스턴스 중복 실행을 막고, 회원 1명당 1트랜잭션으로 재계산해 한 회원의 실패가 회차 전체를 막지 않는다.
 * 점수 계산과 결과 교체는 {@link StudyRecommendationRefreshService}가 맡는다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecommendationRefreshScheduler.class);

    private static final String JOB_NAME = "RecommendationRefreshScheduler";

    // 스케줄러 명세 5.4 한 회차 처리량 "200명 단위 청크".
    static final int CHUNK_SIZE = 200;

    // 스케줄러 명세 0.1 처리량 상한의 실행 시간 상한. 5.4 lockAtMostFor(1시간)보다 짧게 두어 잠금이 풀린 뒤 다른 인스턴스와
    // 겹쳐 실행되지 않게 한다. 남은 회원은 조회 시 1일 경과 재계산이나 다음 회차가 처리한다.
    static final Duration MAX_RUN_DURATION = Duration.ofMinutes(50);

    private final StudyRecommendationRefreshService refreshService;
    private final MemberLearningProfileReplicaRepository learningProfileReplicaRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    private Counter refreshedCounter;
    private Counter failedCounter;
    private Timer runTimer;

    @PostConstruct
    void initMetrics() {
        this.refreshedCounter
            = Counter
                .builder("scheduler.items")
                .tag("job", JOB_NAME)
                .tag("result", "refreshed")
                .register(meterRegistry);
        this.failedCounter
            = Counter.builder("scheduler.items").tag("job", JOB_NAME).tag("result", "failed").register(meterRegistry);
        this.runTimer = Timer.builder("scheduler.run.duration").tag("job", JOB_NAME).register(meterRegistry);
    }

    /**
     * 매일 03:00에 학습 프로필 복제본 전체를 회원 ID 오름차순으로 200명씩 재계산한다. 실행 시간이 50분에 이르면 남은 회원을
     * 처리하지 않고 회차를 끝낸다.
     */
    @Scheduled(
        cron = "0 0 3 * * *",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = JOB_NAME,
        lockAtMostFor = "PT1H"
    )
    public void refreshAll() {
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(MAX_RUN_DURATION);
        int refreshed = 0;
        int failed = 0;
        boolean stoppedByTimeLimit = false;
        long afterMemberId = 0L;

        chunks: while (true) {
            List<Long> memberIds
                = learningProfileReplicaRepository.findMemberIdsAfter(afterMemberId, Limit.of(CHUNK_SIZE));
            if (memberIds.isEmpty()) {
                break;
            }
            for (Long memberId : memberIds) {
                if (!clock.instant().isBefore(deadline)) {
                    stoppedByTimeLimit = true;
                    break chunks;
                }
                try {
                    refreshService.refresh(memberId);
                    refreshedCounter.increment();
                    refreshed++;
                } catch (Exception e) {
                    log.error("추천 결과 재계산 실패. memberId={}", memberId, e);
                    failedCounter.increment();
                    failed++;
                }
                afterMemberId = memberId;
            }
        }

        runTimer.record(Duration.between(startedAt, clock.instant()));
        log.info("추천 결과 갱신 완료. refreshed={}, failed={}, stoppedByTimeLimit={}", refreshed, failed, stoppedByTimeLimit);
    }
}

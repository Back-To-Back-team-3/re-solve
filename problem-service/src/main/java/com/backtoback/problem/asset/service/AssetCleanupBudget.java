package com.backtoback.problem.asset.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 30분 잠금의 절반만 사용하며 시스템 시각 역행에도 실행 상한을 유지한다(스케줄러 §0.1). */
public final class AssetCleanupBudget {
    private static final Duration LIMIT = Duration.ofMinutes(15);
    private final Clock clock;
    private final Instant deadline;
    private final long started = System.nanoTime();

    /**
     * 조회·S3·DB 정리가 공유하는 실행 기한을 시작한다.
     *
     * @param clock 주입된 시계
     */
    public AssetCleanupBudget(Clock clock) {
        this.clock = clock;
        this.deadline = clock.instant().plus(LIMIT);
    }

    /**
     * 다음 단계의 최대 실행 시간을 확보할 수 있는지 확인한다.
     *
     * @param required 다음 단계의 실행 상한
     * @return 중단되지 않았고 충분한 예산이 남았는지 여부
     */
    public boolean hasTime(Duration required) {
        return !Thread.currentThread().isInterrupted() && remaining().compareTo(required) > 0;
    }

    /**
     * DB 단계가 전체 실행 기한 안에 끝날 예산이 남아 있는지 검사한다.
     *
     * @throws IllegalStateException 중단 또는 실행 예산 부족
     */
    public void requireDatabaseTime() {
        if (!hasTime(Duration.ofSeconds(10))) {
            throw new IllegalStateException("파일 정리 실행 예산이 만료되었습니다.");
        }
    }

    /**
     * 매 S3 호출에 전체 잔여 예산과 10초 중 작은 제한을 적용한다.
     *
     * @return 원격 호출 상한
     * @throws IllegalStateException 중단 또는 기한 초과
     */
    public Duration requestTimeout() {
        var remaining = remaining();
        if (Thread.currentThread().isInterrupted() || remaining.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalStateException("파일 정리 실행 예산이 만료되었습니다.");
        }
        return remaining.compareTo(Duration.ofSeconds(10)) < 0 ? remaining : Duration.ofSeconds(10);
    }

    /**
     * 벽시계와 단조 시계 중 먼저 소진된 예산을 반환한다.
     *
     * @return 두 시계 중 작은 잔여 시간
     */
    private Duration remaining() {
        var wall = Duration.between(clock.instant(), deadline);
        var monotonic = LIMIT.minusNanos(System.nanoTime() - started);
        return wall.compareTo(monotonic) < 0 ? wall : monotonic;
    }
}

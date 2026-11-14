package com.backtoback.member.skill.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 제한 시각이 지난 진단을 종료하고 마지막 Draft를 자동 제출한다 (스케줄러 1.1). 인스턴스가 여러 개여도 한 곳에서만 실행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisExpiryScheduler {

    // +3초는 처리 유예다. 접수 기준은 ends_at 그대로라 유예 중 도착한 저장·제출은 DIAGNOSIS_CLOSED다.
    private static final Duration GRACE = Duration.ofSeconds(3);
    private static final int BATCH_SIZE = 100;
    private static final Duration BUDGET = Duration.ofSeconds(20);

    private final DiagnosisAttemptRepository attemptRepository;
    private final DiagnosisService diagnosisService;
    private final Clock clock;

    @Scheduled(
        fixedDelay = 5000,
        initialDelay = 5000
    )
    @SchedulerLock(
        name = "DiagnosisExpiryScheduler",
        lockAtMostFor = "PT30S"
    )
    public void expire() {
        Instant deadline = clock.instant().plus(BUDGET);
        List<Long> ids
            = attemptRepository
                .findExpiredIds(
                    DiagnosisStatus.IN_PROGRESS,
                    LocalDateTime.now(clock).minus(GRACE),
                    PageRequest.of(0, BATCH_SIZE)
                );
        int closed = 0;
        for (Long id : ids) {
            if (clock.instant().isAfter(deadline)) {
                break;
            }
            try {
                if (diagnosisService.expire(id)) {
                    closed++;
                }
            } catch (RuntimeException exception) {
                // 한 건의 실패로 나머지를 멈추지 않는다. 다음 실행에서 시각 조건으로 다시 고른다.
                log.warn("Diagnosis expiry failed: diagnosisId={}, cause={}", id, exception.getClass().getSimpleName());
            }
        }
        if (closed > 0) {
            log.info("Diagnosis expiry closed {} attempts", closed);
        }
    }
}

package com.backtoback.member.skill.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.member.skill.config.SkillProperties;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore.CooldownRelease;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 진단 재응시 쿨타임이 끝난 태그를 알린다 (스케줄러 1.3, 정책 및 상태 8.3 레벨 알림). 인스턴스가 여러 개여도 한 곳에서만 실행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisCooldownReleaseScheduler {

    private static final int BATCH_SIZE = 100;

    private final DiagnosisSubmissionStore submissionStore;
    private final DiagnosisScoringService scoringService;
    private final SkillProperties properties;
    private final Clock clock;

    @Scheduled(
        fixedDelay = 600_000,
        initialDelay = 600_000
    )
    @SchedulerLock(
        name = "DiagnosisCooldownReleaseScheduler",
        lockAtMostFor = "PT8M"
    )
    public void release() {
        LocalDateTime closedBefore = LocalDateTime.now(clock).minus(properties.diagnosisCooldown());
        int notified = 0;
        for (CooldownRelease release : submissionStore.findCooldownReleases(closedBefore, BATCH_SIZE)) {
            try {
                if (scoringService.notifyCooldownReleased(release.memberId(), release.attemptId(), closedBefore)) {
                    notified++;
                }
            } catch (RuntimeException exception) {
                log
                    .warn(
                        "Diagnosis cooldown release failed: diagnosisId={}, cause={}",
                        release.attemptId(),
                        exception.getClass().getSimpleName()
                    );
            }
        }
        if (notified > 0) {
            log.info("Diagnosis cooldown released {} attempts", notified);
        }
    }
}

package com.backtoback.member.skill.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.member.skill.store.DiagnosisSubmissionStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 채점 결과 소비가 놓친 응시(이벤트 순서·DLQ 재처리 뒤)를 확정한다 (스케줄러 1.2). 기본 경로는 결과 소비 트랜잭션의 즉시 확정이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisScoringScheduler {

    private static final int BATCH_SIZE = 100;

    private final DiagnosisSubmissionStore submissionStore;
    private final DiagnosisScoringService scoringService;

    @Scheduled(
        fixedDelay = 60_000,
        initialDelay = 60_000
    )
    @SchedulerLock(
        name = "DiagnosisScoringScheduler",
        lockAtMostFor = "PT50S"
    )
    public void score() {
        int scored = 0;
        // shortcut: 계속 실패하는 응시(데이터 손상)는 롤백돼 매번 다시 뽑혀 배치를 차지할 수 있음, 운영 알림을 붙일 때 실패 표시·재시도 한도를 넣는다.
        for (Long id : submissionStore.findScorableAttemptIds(BATCH_SIZE)) {
            try {
                if (scoringService.scoreIfReady(id)) {
                    scored++;
                }
            } catch (RuntimeException exception) {
                log
                    .warn(
                        "Diagnosis scoring failed: diagnosisId={}, cause={}",
                        id,
                        exception.getClass().getSimpleName()
                    );
            }
        }
        if (scored > 0) {
            log.info("Diagnosis scoring confirmed {} attempts", scored);
        }
    }
}

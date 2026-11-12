package com.backtoback.member.skill.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.member.service.LearningProfileService;
import com.backtoback.member.member.service.MemberEventPublisher;
import com.backtoback.member.member.service.TagLevelChangedPayload;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisProblem;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.domain.LevelChangeReason;
import com.backtoback.member.skill.domain.MemberLevelHistory;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.domain.TagLevel;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.DiagnosisProblemRepository;
import com.backtoback.member.skill.repository.MemberLevelHistoryRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore.ResultTarget;

import lombok.RequiredArgsConstructor;

/**
 * 진단 채점 결과 반영과 결과 확정 (정책 및 상태 8.3, 스케줄러 1.2). 같은 응시는 응시 행 잠금으로 한 줄로 처리하고, 확정은
 * {@code CLOSED}이며 모든 제출이 종결됐을 때 한 번만 한다.
 */
@Service
@RequiredArgsConstructor
public class DiagnosisScoringService {

    public static final String JUDGED = "JUDGED";
    public static final String FAILED = "FAILED";

    private final DiagnosisAttemptRepository attemptRepository;
    private final DiagnosisProblemRepository problemRepository;
    private final DiagnosisSubmissionStore submissionStore;
    private final MemberTagLevelRepository tagLevelRepository;
    private final MemberLevelHistoryRepository historyRepository;
    private final MemberRepository memberRepository;
    private final MemberEventPublisher memberEventPublisher;
    private final LearningProfileService learningProfileService;
    private final Clock clock;

    /**
     * 진단 제출의 채점 결과(J5 {@code JUDGED}·J4 {@code FAILED})를 반영한다. 공통 소비기의 트랜잭션 안에서 호출된다.
     * 저장된 회차보다 작거나 같은 {@code judgeAttempt}는 무시하고, 이미 {@code SCORED}인 응시는 제출 기록만 갱신한다.
     *
     * @param verdict {@code FAILED}면 {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyResult(
        long diagnosisId,
        long diagnosisSubmissionId,
        long memberId,
        long problemId,
        long submissionId,
        int judgeAttempt,
        String status,
        String verdict
    ) {
        DiagnosisAttempt attempt
            = attemptRepository
                .findForUpdateById(diagnosisId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown diagnosis for judging result"));
        ResultTarget target
            = submissionStore
                .findResultTarget(diagnosisSubmissionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown diagnosis submission"));
        // 재전달로 해결되지 않는 불일치는 DLQ로 보내 확인한다.
        if (attempt.getMemberId() != memberId || target.attemptId() != diagnosisId || target.problemId() != problemId) {
            throw new IllegalArgumentException("Judging result does not match the diagnosis submission");
        }
        if (target.judgeAttempt() != null && judgeAttempt <= target.judgeAttempt()) {
            return;
        }
        LocalDateTime now = now();
        submissionStore.recordResult(diagnosisSubmissionId, submissionId, judgeAttempt, status, verdict, now);
        // shortcut: SCORED 이후 도착한 재채점은 기록만 하고 레벨에 반영하지 않음, 진단 재채점 적용 정책이 정해지면 재확정한다.
        if (attempt.getStatus() == DiagnosisStatus.SCORED) {
            return;
        }
        problemRepository
            .findAllByDiagnosisAttemptIdOrderByDifficultyAsc(diagnosisId)
            .stream()
            .filter(problem -> problem.getProblemId() == problemId)
            .findFirst()
            .ifPresent(problem -> problem.applyResult(submissionId, verdict, target.latest()));
        scoreIfReady(attempt);
    }

    /**
     * 채점 확정 보정 (스케줄러 1.2). 응시 1건당 1트랜잭션이다.
     *
     * @return 이번에 확정했으면 {@code true}
     */
    @Transactional
    public boolean scoreIfReady(Long diagnosisId) {
        Optional<DiagnosisAttempt> attempt = attemptRepository.findForUpdateById(diagnosisId);
        return attempt.isPresent() && scoreIfReady(attempt.get());
    }

    /**
     * {@code CLOSED}이고 모든 제출이 종결됐으면 결과를 확정한다. 호출하는 쪽이 응시 행을 잠근다.
     * <ul>
     * <li>점수: 정답 문항의 난이도 합(총 6점). 결과 레벨: 정답 문항 중 가장 높은 난이도, 정답이 없으면 {@code LV1}</li>
     * <li>반영 레벨: {@code max(현재 레벨, 결과 레벨)}. 바뀌면 레벨 이력({@code DIAGNOSIS})을 남긴다.</li>
     * <li>{@code FAILED}가 1건이라도 있으면 쿨타임을 면제한다.</li>
     * <li>{@code TagLevelChanged}(M5, 레벨이 그대로여도 결과 알림)와 {@code LearningProfileUpdated}(M4)를 기록한다.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean scoreIfReady(DiagnosisAttempt attempt) {
        if (attempt.getStatus() != DiagnosisStatus.CLOSED || submissionStore.countPending(attempt.getId()) > 0) {
            return false;
        }
        List<DiagnosisProblem> problems
            = problemRepository.findAllByDiagnosisAttemptIdOrderByDifficultyAsc(attempt.getId());
        int highest
            = problems
                .stream()
                .filter(DiagnosisProblem::isAccepted)
                .mapToInt(DiagnosisProblem::getDifficulty)
                .max()
                .orElse(1);
        BigDecimal score
            = BigDecimal
                .valueOf(
                    problems
                        .stream()
                        .filter(DiagnosisProblem::isAccepted)
                        .mapToInt(DiagnosisProblem::getDifficulty)
                        .sum()
                )
                .setScale(2);
        TagLevel result = TagLevel.values()[highest];
        boolean cooldownExempt = submissionStore.anyFailed(attempt.getId());

        // LearningProfileUpdated 순번이 다른 변경과 겹치지 않도록 회원 행을 잠근다(응시 → 회원 순서).
        memberRepository.findForUpdateById(attempt.getMemberId());
        MemberTagLevel tagLevel
            = tagLevelRepository
                .findByMemberIdAndTagId(attempt.getMemberId(), attempt.getTagId())
                .orElseThrow(() -> new IllegalStateException("Diagnosis tag level is missing"));
        TagLevel before = tagLevel.getLevel();
        TagLevel applied = tagLevel.applyDiagnosis(result, cooldownExempt);
        attempt.score(score, result, applied, cooldownExempt, now());
        if (applied != before) {
            historyRepository.save(MemberLevelHistory.diagnosis(tagLevel, before, attempt.getId()));
        }
        memberEventPublisher
            .tagLevelChanged(
                new TagLevelChangedPayload(
                    String.valueOf(attempt.getMemberId()),
                    String.valueOf(attempt.getTagId()),
                    attempt.getTagName(),
                    before.name(),
                    applied.name(),
                    LevelChangeReason.DIAGNOSIS.name(),
                    String.valueOf(attempt.getId()),
                    null
                )
            );
        learningProfileService.tagLevelsChanged(attempt.getMemberId());
        return true;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}

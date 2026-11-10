package com.backtoback.member.skill.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.service.MemberService;
import com.backtoback.member.skill.client.ProblemDiagnosisClient;
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.config.SkillProperties;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisProblem;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.DiagnosisProblemRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;
import com.backtoback.member.skill.store.DiagnosisDraftStore;
import com.backtoback.member.skill.store.DiagnosisDraftStore.Draft;
import com.backtoback.member.skill.store.DiagnosisDraftStore.LastSubmission;
import com.backtoback.member.skill.store.DiagnosisDraftStore.SaveResult;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class DiagnosisService {

    /**
     * 진단 제출 코드 상한 (이벤트 계약 M6): UTF-8 64KB.
     */
    private static final int MAX_SOURCE_BYTES = 64 * 1024;

    private final DiagnosisAttemptRepository attemptRepository;
    private final DiagnosisProblemRepository problemRepository;
    private final MemberTagLevelRepository tagLevelRepository;
    private final DiagnosisDraftStore draftStore;
    private final MemberService memberService;
    private final ProblemTagClient problemTagClient;
    private final ProblemDiagnosisClient problemDiagnosisClient;
    private final SkillProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 진단 시작 (API 명세서 1.5.1). 원격 출제는 회원 행을 잠그기 전에 끝내고, 잠근 뒤 조건을 다시 확인해 응시와 문항을 저장한다.
     * 같은 태그의 동시 시작은 {@code uk_diagnosis_attempts_member_active_tag}로 한 건만 남는다.
     */
    public StartedDiagnosis start(Long memberId, Long tagId) {
        boolean entered = checkStartable(memberId, tagId, now()).isPresent();
        if (!entered && !problemTagClient.exists(tagId)) {
            throw new MemberException(MemberErrorCode.LEVEL_TAG_NOT_FOUND);
        }
        List<Candidate> picked = pick(problemDiagnosisClient.candidates(tagId));
        try {
            return transactionTemplate.execute(status -> {
                memberService.lockActiveMember(memberId);
                LocalDateTime now = now();
                if (checkStartable(memberId, tagId, now).isEmpty()) {
                    tagLevelRepository.save(MemberTagLevel.enterForDiagnosis(memberId, tagId, now));
                }
                DiagnosisAttempt attempt = attemptRepository.saveAndFlush(DiagnosisAttempt.start(memberId, tagId, now));
                List<DiagnosisProblem> problems = new ArrayList<>();
                for (Candidate candidate : picked) {
                    problems
                        .add(
                            new DiagnosisProblem(
                                attempt.getId(),
                                Long.valueOf(candidate.problemId()),
                                Long.valueOf(candidate.problemRevisionId()),
                                candidate.difficulty()
                            )
                        );
                }
                problemRepository.saveAll(problems);
                return new StartedDiagnosis(attempt, picked, now);
            });
        } catch (DataIntegrityViolationException exception) {
            throw new MemberException(MemberErrorCode.DIAGNOSIS_ALREADY_IN_PROGRESS, exception);
        }
    }

    /**
     * 진단 상세·복구 (API 명세서 1.5.2). 본인 응시만 조회한다.
     */
    @Transactional(readOnly = true)
    public DiagnosisDetail getDetail(Long memberId, Long diagnosisId) {
        DiagnosisAttempt attempt = getAttempt(memberId, diagnosisId);
        return new DiagnosisDetail(
            attempt,
            problemRepository.findAllByDiagnosisAttemptIdOrderByDifficultyAsc(attempt.getId()),
            draftStore.findLatest(attempt.getId()),
            draftStore.findLastSubmissions(attempt.getId()),
            now()
        );
    }

    /**
     * 진단 문제 본문 (API 명세서 1.5.3). 진단에 고정한 회차를 조회하며 레벨 접근 제한을 적용하지 않는다.
     */
    @Transactional(readOnly = true)
    public JsonNode getProblemContent(Long memberId, Long diagnosisId, Long problemId) {
        DiagnosisProblem problem = getProblem(getAttempt(memberId, diagnosisId), problemId);
        return problemDiagnosisClient.content(diagnosisId, problem.getProblemId(), problem.getProblemRevisionId());
    }

    /**
     * 진단 Draft 저장 (API 명세서 1.5.4). 제한 시각이 지났거나 종료된 진단은 받지 않는다.
     */
    @Transactional
    public SaveResult saveDraft(
        Long memberId,
        Long diagnosisId,
        Long problemId,
        String language,
        String sourceCode,
        long seq
    ) {
        // 제한 시각은 잠금을 기다린 뒤가 아니라 요청을 접수한 시각으로 판단한다(API 명세서 1.5.4).
        LocalDateTime receivedAt = now();
        DiagnosisAttempt attempt
            = attemptRepository
                .findForUpdateByIdAndMemberId(diagnosisId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND));
        getProblem(attempt, problemId);
        if (!attempt.isOpenAt(receivedAt)) {
            throw new MemberException(MemberErrorCode.DIAGNOSIS_CLOSED);
        }
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new MemberException(MemberErrorCode.CODE_TOO_LARGE);
        }
        // shortcut: 응시자당 초당 1회 Rate Limit 미적용(seq 조건으로 순서는 보장), 부하 측정에서 저장이 몰리면 Redis 제한을 넣는다.
        return draftStore.save(attempt.getId(), problemId, language, sourceCode, seq, receivedAt);
    }

    /**
     * 진단 이력 (API 명세서 1.5.7). {@code tagId}가 없으면 전체 태그다.
     */
    @Transactional(readOnly = true)
    public Page<DiagnosisAttempt> getHistory(Long memberId, Long tagId, Pageable pageable) {
        return attemptRepository.search(memberId, tagId, pageable);
    }

    /**
     * 시작 조건을 확인한다. 진입한 태그면 그 레벨을 돌려준다.
     */
    private Optional<MemberTagLevel> checkStartable(Long memberId, Long tagId, LocalDateTime now) {
        Optional<MemberTagLevel> tagLevel = tagLevelRepository.findByMemberIdAndTagId(memberId, tagId);
        if (tagLevel.isPresent()) {
            if (!tagLevel.get().getLevel().isDiagnosable()) {
                throw new MemberException(MemberErrorCode.DIAGNOSIS_NOT_ELIGIBLE);
            }
            LocalDateTime lastEndedAt = tagLevel.get().getLastDiagnosisEndedAt();
            if (lastEndedAt != null) {
                LocalDateTime cooldownEndsAt = lastEndedAt.plus(properties.diagnosisCooldown());
                if (now.isBefore(cooldownEndsAt)) {
                    long seconds = Duration.between(now, cooldownEndsAt).toSeconds();
                    throw new DiagnosisCooldownException(Math.max(1, seconds));
                }
            }
        }
        if (attemptRepository.existsByMemberIdAndTagIdAndStatus(memberId, tagId, DiagnosisStatus.IN_PROGRESS)) {
            throw new MemberException(MemberErrorCode.DIAGNOSIS_ALREADY_IN_PROGRESS);
        }
        return tagLevel;
    }

    /**
     * 난이도 1·2·3마다 후보가 정확히 하나씩 있어야 한다(레벨 판정에 세 문항이 모두 필요).
     */
    private static List<Candidate> pick(List<Candidate> candidates) {
        List<Candidate> picked = new ArrayList<>();
        for (int difficulty = 1; difficulty <= 3; difficulty++) {
            int target = difficulty;
            List<Candidate> matched
                = candidates
                    .stream()
                    .filter(candidate -> candidate != null && candidate.difficulty() == target)
                    .toList();
            if (matched.isEmpty()) {
                throw new MemberException(MemberErrorCode.DIAGNOSIS_PROBLEMS_UNAVAILABLE);
            }
            Candidate candidate = matched.get(0);
            if (matched.size() > 1 || !isId(candidate.problemId()) || !isId(candidate.problemRevisionId())) {
                throw new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            picked.add(candidate);
        }
        return picked;
    }

    // Long 범위 안(18자리 이하)만 받는다.
    private static boolean isId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,17}");
    }

    private DiagnosisAttempt getAttempt(Long memberId, Long diagnosisId) {
        return attemptRepository
            .findByIdAndMemberId(diagnosisId, memberId)
            .orElseThrow(() -> new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND));
    }

    private DiagnosisProblem getProblem(DiagnosisAttempt attempt, Long problemId) {
        return problemRepository
            .findAllByDiagnosisAttemptIdOrderByDifficultyAsc(attempt.getId())
            .stream()
            .filter(problem -> problem.getProblemId().equals(problemId))
            .findFirst()
            .orElseThrow(() -> new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 시작한 진단과 출제 시 받은 제목. 제목은 저장하지 않고 시작 응답에만 싣는다.
     */
    public record StartedDiagnosis(DiagnosisAttempt attempt, List<Candidate> problems, LocalDateTime serverNow) {
    }

    /**
     * 진단 상세 계산 결과.
     */
    public record DiagnosisDetail(
        DiagnosisAttempt attempt,
        List<DiagnosisProblem> problems,
        Map<Long, Draft> drafts,
        Map<Long, LastSubmission> lastSubmissions,
        LocalDateTime serverNow
    ) {
    }
}

package com.backtoback.judge.submission.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.ExecutionRuntime;
import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.repository.JudgeJobRepository;
import com.backtoback.judge.judging.repository.JudgeTestResultRepository;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.repository.SubmissionRepository;
import com.backtoback.judge.submission.service.dto.StoredSubmission;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest;

import lombok.RequiredArgsConstructor;

/** 원격 호출 없이 제출·채점 작업·테스트 결과 자리를 원자적으로 저장한다. */
@Service
@RequiredArgsConstructor
public class SubmissionStorageService {
    private final SubmissionRepository submissions;
    private final JudgeJobRepository jobs;
    private final JudgeTestResultRepository tests;
    private final ExecutionRuntimeRepository runtimes;
    private final Clock clock;

    /**
     * 외부 검증을 마친 제출과 실행 전 저장 모델을 같은 트랜잭션에서 만든다.
     * 실행 이력은 종결한 실제 실행 시도가 생겼을 때 별도로 추가한다.
     *
     * @param request 고정 원본·테스트·처리 기한
     * @return 생성한 제출과 작업 식별자
     * @throws IllegalStateException 스냅샷 해시와 원본이 불일치하거나 등록한 행을 조회할 수 없는 경우
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 언어인 경우
     * @throws IllegalArgumentException 제출이나 테스트·처리 기한이 유효하지 않은 경우
     */
    @Transactional
    public StoredSubmission register(SubmissionStorageRequest request) {
        Assert.notNull(request, "저장 요청은 필수입니다.");
        Assert.notEmpty(request.tests(), "고정 회차의 테스트는 필수입니다.");
        var now = now();
        Submission submission = submissions.save(Submission.queued(request.submission()));
        if (request.runtime() != null) {
            submission.fixRuntime(resolveRuntime(request.runtime(), now));
        }
        JudgeJob job = jobs.save(JudgeJob.pending(submission, now, request.deadlineAt()));
        List<JudgeTestResult> pending = request.tests().stream().map(test -> {
            Assert.notNull(test, "테스트 메타데이터는 필수입니다.");
            return JudgeTestResult.pending(job, test.testAssetId(), test.testOrder(), test.sample());
        }).toList();
        tests.saveAllAndFlush(pending);
        return new StoredSubmission(submission.getId(), job.getId(), job.getJudgeAttempt());
    }

    /**
     * 제출 행을 잠그고 미확정 실행 환경을 한 번만 연결한다.
     * 스칼라 잠금 조회로 최신 참조를 확인하고, 이미 확정한 환경은 새 후보로 덮어쓰지 않는다.
     *
     * @param submissionId 환경을 확정할 제출 ID
     * @param selected 실행기가 선택한 환경
     * @return 해당 제출에 확정된 실행 환경
     * @throws IllegalStateException 스냅샷 해시와 원본이 불일치하거나 등록한 행을 조회할 수 없는 경우
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 실행 환경 언어인 경우
     * @throws IllegalArgumentException 제출이 없거나 연결할 환경이 올바르지 않은 경우
     */
    @Transactional
    public JudgeRuntime fixRuntime(long submissionId, JudgeRuntime selected) {
        var reference
            = submissions
                .findRuntimeForUpdate(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("실행 환경을 연결할 제출이 없습니다."));
        ExecutionRuntime runtime
            = reference.runtimeId() == null ? resolveRuntime(selected, now())
                : runtimes
                    .findCurrentById(reference.runtimeId())
                    .orElseThrow(() -> new IllegalStateException("제출에 확정된 실행 환경이 없습니다."));
        Submission submission
            = submissions
                .findCurrentForUpdate(reference.submissionId())
                .orElseThrow(() -> new IllegalArgumentException("실행 환경을 연결할 제출이 없습니다."));
        // 최신 참조만 관리 엔티티에 반영하고, 다른 속성의 미저장 변경은 유지한다.
        submission.fixRuntime(runtime);
        return runtime.toRuntime();
    }

    /**
     * 이름·버전·옵션까지 같은 스냅샷을 재사용하거나 새 스냅샷을 저장한다.
     *
     * @param selected 검증할 환경
     * @param now 기록 시각
     * @return 영속 실행 환경
     */
    private ExecutionRuntime resolveRuntime(JudgeRuntime selected, LocalDateTime now) {
        ExecutionRuntime snapshot = ExecutionRuntime.snapshot(selected, now);
        runtimes.insertIfAbsent(snapshot);
        ExecutionRuntime stored
            = runtimes
                .findBySnapshotHash(snapshot.getSnapshotHash())
                .orElseThrow(() -> new IllegalStateException("등록한 실행 환경 스냅샷을 찾을 수 없습니다."));
        Assert.state(stored.matches(selected), "실행 환경 스냅샷 해시와 원본 속성이 일치하지 않습니다.");
        return stored;
    }

    /**
     * 공통 감사 시각과 같은 서울 시간·마이크로초 정밀도로 현재 시각을 구한다.
     *
     * @return 저장에 사용할 현재 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}

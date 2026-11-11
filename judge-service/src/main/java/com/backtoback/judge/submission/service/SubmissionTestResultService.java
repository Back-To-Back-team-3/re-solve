package com.backtoback.judge.submission.service;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.client.TestAssetClient;
import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.submission.dto.SubmissionTestResultsResponse;

import lombok.RequiredArgsConstructor;

/** 권한·회차 확인 뒤 공개 테스트 원문만 조회한다. 원격 호출 중 DB 트랜잭션을 유지하지 않는다. */
@Service
@RequiredArgsConstructor
public class SubmissionTestResultService {
    private final SubmissionHistoryReader history;
    private final SubmissionJudgingDataClient dataClient;
    private final TestAssetClient assets;

    /**
     * 소유권과 회차를 확인한 뒤 공개 테스트 원문만 조회한다. 원격 조회 중 DB 잠금을 유지하지 않는다.
     *
     * @param memberId 조회 회원 ID
     * @param submissionId 제출 ID
     * @param judgeAttempt 채점 회차
     * @return 공개 범위에 맞춘 테스트별 결과
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 본인 일반 제출 또는 회차가 없는 경우;
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 고정 채점 자료·원문을 조회하거나 검증할 수 없거나 조회가 중단된 경우
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubmissionTestResultsResponse read(long memberId, long submissionId, int judgeAttempt) {
        var snapshot = history.read(memberId, submissionId);
        var attempt
            = snapshot
                .attempts()
                .stream()
                .filter(value -> value.job().getJudgeAttempt() == judgeAttempt)
                .findFirst()
                .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
        Map<String, SubmissionJudgingDataResponse.TestCase> cases = Map.of();
        if (attempt.tests().stream().anyMatch(JudgeTestResult::isSample)) {
            var data
                = dataClient.find(snapshot.submission().getProblemId(), snapshot.submission().getProblemRevisionId());
            cases
                = data
                    .cases()
                    .stream()
                    .collect(
                        Collectors.toMap(SubmissionJudgingDataResponse.TestCase::testAssetId, Function.identity())
                    );
        }
        var content = new ArrayList<SubmissionTestResultsResponse.TestResult>();
        var status = attempt.job().getStatus();
        for (var test : attempt.tests()) {
            boolean visible
                = status != JudgeJob.Status.PENDING && status != JudgeJob.Status.RETRY_WAITING
                    && test.getTokenStatus() == JudgeTestResult.TokenStatus.DONE && test.getCreatedTryCount() != null
                    && (test.getCreatedTryCount() == attempt.job().getTryCount()
                        || status == JudgeJob.Status.SUCCEEDED);
            var verdict = visible && test.getVerdict() != JudgeVerdict.SYSTEM_ERROR ? test.getVerdict() : null;
            if (!test.isSample()) {
                content.add(SubmissionTestResultsResponse.HiddenTest.from(test.getTestOrder(), verdict));
                continue;
            }
            var fixed = cases.get(Long.toString(test.getTestAssetId()));
            if (fixed == null || !Boolean.TRUE.equals(fixed.isSample()) || fixed.testOrder() != test.getTestOrder()) {
                throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            try {
                var source = assets.read(fixed.asset());
                content.add(SubmissionTestResultsResponse.PublicTest.from(test, verdict, source));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
        }
        return SubmissionTestResultsResponse.from(content);
    }
}

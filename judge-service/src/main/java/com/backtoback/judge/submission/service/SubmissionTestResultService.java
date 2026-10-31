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
        for (var test : attempt.tests()) {
            boolean current
                = attempt.job().getStatus() != JudgeJob.Status.PENDING
                    && attempt.job().getStatus() != JudgeJob.Status.RETRY_WAITING && test.getCreatedTryCount() != null
                    && test.getCreatedTryCount() == attempt.job().getTryCount()
                    && test.getTokenStatus() == JudgeTestResult.TokenStatus.DONE;
            var verdict = current && test.getVerdict() != JudgeVerdict.SYSTEM_ERROR ? test.getVerdict() : null;
            if (!test.isSample()) {
                content
                    .add(
                        new SubmissionTestResultsResponse.HiddenTest(
                            test.getTestOrder(),
                            verdict == null ? null : verdict == JudgeVerdict.AC
                        )
                    );
                continue;
            }
            var fixed = cases.get(Long.toString(test.getTestAssetId()));
            if (fixed == null || !Boolean.TRUE.equals(fixed.isSample()) || fixed.testOrder() != test.getTestOrder()) {
                throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            var source = assets.read(fixed.asset());
            content
                .add(
                    new SubmissionTestResultsResponse.PublicTest(
                        test.getTestOrder(),
                        true,
                        verdict,
                        verdict == null ? null : test.getTimeMs(),
                        verdict == null ? null : test.getMemoryKb(),
                        source.argumentTexts(),
                        source.expectedReturnText(),
                        verdict == null ? null : test.getActualOutput()
                    )
                );
        }
        return new SubmissionTestResultsResponse(content);
    }
}

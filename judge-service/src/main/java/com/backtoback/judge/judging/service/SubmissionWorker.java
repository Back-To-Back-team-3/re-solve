package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.client.TestAssetClient;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.service.dto.SubmissionExecutionInput;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** DB 잠금 밖에서 고정 회차의 전체 테스트를 실행한다. 코드 실패는 나머지 테스트를 중단하지 않는다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionWorker {
    private final SubmissionExecutionStore store;
    private final SubmissionJudgingDataClient dataClient;
    private final TestAssetClient assets;
    private final JudgeLanguageService languages;
    private final List<ExecutionCodeGenerator> generators;
    private final JudgeClient client;
    private final JudgeResultEvaluator evaluator;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean runOnce() {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        var claimed = store.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        var lease = claimed.orElseThrow();
        var heartbeat = new SubmissionHeartbeat(lease, store, clock);
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", "judge-job-" + lease.jobId());
        String errorCode = "TEST_DATA_UNAVAILABLE";
        try {
            if (!heartbeat.active()) {
                return true;
            }
            var input = store.input(lease);
            var data = dataClient.find(input.problemId(), input.revisionId());
            store.validateCases(lease, data);
            languages.requireRuntime(input.language(), input.runtime());
            if (!heartbeat.active() || !store.prepare(lease)) {
                return true;
            }
            var generator
                = generators
                    .stream()
                    .filter(value -> value.language().getCode().equals(input.language()))
                    .findFirst()
                    .orElseThrow();
            String compileMessage = null;
            for (var test : data
                .cases()
                .stream()
                .sorted(Comparator.comparingInt(SubmissionJudgingDataResponse.TestCase::testOrder))
                .toList()) {
                errorCode = "TEST_DATA_UNAVAILABLE";
                if (!heartbeat.active()) {
                    return true;
                }
                var content = assets.read(test.asset());
                errorCode = "JUDGE0_UNAVAILABLE";
                var result = execute(heartbeat, input, data, test, content, generator);
                if (result.isEmpty()) {
                    return true;
                }
                if (result.orElseThrow().verdict() == JudgeVerdict.SYSTEM_ERROR) {
                    store.fail(lease, errorCode);
                    return true;
                }
                if (result.orElseThrow().verdict() == JudgeVerdict.CE && compileMessage == null) {
                    compileMessage = result.orElseThrow().compileMessage();
                }
            }
            store.complete(lease, data, compileMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // 원문·Judge0 응답은 로그나 오류 코드에 포함하지 않는다.
            log.warn("일반 채점 실패: jobId={}, type={}", lease.jobId(), failure.getClass().getSimpleName());
            store.fail(lease, errorCode);
        } finally {
            if (previous == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previous);
            }
        }
        return true;
    }

    private Optional<JudgeTestResult> execute(
        SubmissionHeartbeat heartbeat,
        SubmissionExecutionInput input,
        SubmissionJudgingDataResponse data,
        SubmissionJudgingDataResponse.TestCase test,
        SubmissionJudgingDataResponse.TestContent content,
        ExecutionCodeGenerator generator
    )
        throws InterruptedException {
        var source = generator.generate(data.functionSpec(), input.sourceCode(), content.argumentTexts());
        var limits = data.executionLimits();
        var lease = heartbeat.lease();
        if (!heartbeat.active() || !store.beginCreate(lease, test.testOrder())) {
            return Optional.empty();
        }
        JudgeExecutionCreateResponse created;
        try {
            created
                = client
                    .create(source.toRequest(input.runtime(), limits.timeMs(), limits.wallTimeMs(), limits.memoryKb()));
        } catch (JudgeException failure) {
            // 생성 응답 유실은 UNKNOWN을 보존한다. 새 토큰을 만들지 않는다.
            return Optional.empty();
        }
        if (created == null || !input.runtime().equals(created.runtime()) || created.token() == null
            || created.token().isBlank() || created.token().length() > 64
            || !store.saveToken(lease, test.testOrder(), created.token())) {
            return Optional.empty();
        }
        while (!Thread.currentThread().isInterrupted() && heartbeat.active()) {
            JudgeTestResult result;
            try {
                result
                    = evaluator
                        .evaluate(
                            client.find(created.token()),
                            data.functionSpec().returnType(),
                            content.expectedReturnText(),
                            limits.memoryKb(),
                            limits.outputLimitKb()
                        );
            } catch (JudgeException failure) {
                Thread.sleep(1000);
                continue;
            }
            if (result.verdict() != null) {
                return store.saveResult(lease, test.testOrder(), result) ? Optional.of(result) : Optional.empty();
            }
            Thread.sleep(1000);
        }
        return Optional.empty();
    }
}

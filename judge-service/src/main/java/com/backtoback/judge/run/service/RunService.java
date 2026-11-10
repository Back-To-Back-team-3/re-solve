package com.backtoback.judge.run.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.client.TestAssetClient;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestCase;
import com.backtoback.judge.judging.service.JudgeClient;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.run.dto.RunRequest;
import com.backtoback.judge.run.dto.RunResponse;
import com.backtoback.judge.run.store.RunLockStore;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.service.SubmissionAccessService;

import lombok.extern.slf4j.Slf4j;

/** 사용자별 제한 안에서 고정 회차의 공개 예제와 직접 입력만 동기 실행한다. (API 명세서 §3.1.8) */
@Service
@Slf4j
public class RunService {
    private static final long RUN_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();
    private final RunLockStore locks;
    private final SubmissionAccessService access;
    private final SubmissionJudgingDataClient dataClient;
    private final TestAssetClient assets;
    private final List<ExecutionCodeGenerator> generators;
    private final JudgeClient client;
    private final JudgeResultEvaluator evaluator;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats;

    /** 동기 응답 기한과 실행 소유권 갱신의 실행기를 분리한다. */
    public RunService(
        RunLockStore locks,
        SubmissionAccessService access,
        SubmissionJudgingDataClient dataClient,
        TestAssetClient assets,
        List<ExecutionCodeGenerator> generators,
        JudgeClient client,
        JudgeResultEvaluator evaluator,
        @Qualifier("runExecutor") ExecutorService executor,
        @Qualifier("runHeartbeatExecutor") ScheduledExecutorService heartbeats
    ) {
        this.locks = locks;
        this.access = access;
        this.dataClient = dataClient;
        this.assets = assets;
        this.generators = generators;
        this.client = client;
        this.evaluator = evaluator;
        this.executor = executor;
        this.heartbeats = heartbeats;
    }

    /** HTTP 요청의 전체 기한을 넘기면 추가 실행을 중단하고, 종료한 작업이 자신의 제한을 해제한다. */
    public RunResponse run(long memberId, RunRequest request) {
        request.validate();
        var language = JudgeLanguage.fromCode(request.language());
        long problemId = SubmissionCreateRequest.parseId(request.problemId());
        long revisionId = SubmissionCreateRequest.parseId(request.problemRevisionId());
        long deadline = System.nanoTime() + RUN_TIMEOUT_NANOS;
        var context = MDC.getCopyOfContextMap();
        var future = executor.submit(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return withLease(
                    memberId,
                    deadline,
                    guard -> execute(memberId, problemId, revisionId, language, request, guard)
                );
            } finally {
                MDC.clear();
            }
        });
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new JudgeException(JudgeErrorCode.RUN_TIMEOUT);
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof JudgeException judgeFailure) {
                throw judgeFailure;
            }
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw unavailable();
        }
    }

    /** 실행이 살아 있는 동안만 토큰을 갱신하고 종료 시 소유 토큰을 비교해 정리한다. */
    private RunResponse withLease(long memberId, long deadline, RunWork work) throws Exception {
        var alive = new AtomicBoolean(true);
        Runnable guard = () -> {
            if (System.nanoTime() - deadline >= 0) {
                throw new JudgeException(JudgeErrorCode.RUN_TIMEOUT);
            }
            if (!alive.get() || Thread.currentThread().isInterrupted()) {
                throw unavailable();
            }
        };
        guard.run();
        var lease = locks.acquire(memberId);
        try {
            var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                try {
                    if (System.nanoTime() - deadline >= 0 || !locks.heartbeat(lease)) {
                        alive.set(false);
                    }
                } catch (JudgeException failure) {
                    alive.set(false);
                }
            }, 10, 10, TimeUnit.SECONDS);
            try {
                return work.run(guard);
            } finally {
                heartbeat.cancel(false);
            }
        } finally {
            try {
                // 취소의 인터럽트 상태를 잠시 걷어 Redis 해제 호출을 완료한다.
                boolean interrupted = Thread.interrupted();
                try {
                    locks.release(lease);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (JudgeException failure) {
                log.warn("Run 제한 해제 실패. 예약 정리에서 다시 확인한다.");
            }
        }
    }

    /** 접근과 입력을 먼저 검증하고 준비한 실행만 Judge0에 전달한다. */
    private RunResponse execute(
        long memberId,
        long problemId,
        long revisionId,
        JudgeLanguage language,
        RunRequest request,
        Runnable guard
    )
        throws InterruptedException {
        var runtime = access.runRuntime(memberId, problemId, revisionId, request.language());
        guard.run();
        var data = dataClient.find(problemId, revisionId);
        var generator
            = generators
                .stream()
                .filter(value -> value.language() == language)
                .findFirst()
                .orElseThrow(() -> new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE));
        var inputs = prepareInputs(request, data, generator, guard);
        List<RunResponse.Result> results = new ArrayList<>();
        String compileMessage = null;
        for (var input : inputs) {
            var result = executeCase(input, runtime, data, guard);
            results
                .add(
                    new RunResponse.Result(
                        input.assetId() == null ? "CUSTOM" : "SAMPLE",
                        input.assetId(),
                        input.index(),
                        result.verdict(),
                        result.actualReturnText(),
                        input.expected(),
                        result.timeMs(),
                        result.memoryKiB(),
                        result.debugOutput()
                    )
                );
            if (compileMessage == null && result.verdict() == JudgeVerdict.CE) {
                compileMessage = result.compileMessage();
            }
        }
        guard.run();
        return new RunResponse(List.copyOf(results), compileMessage);
    }

    /** 사용자 입력 오류는 파일 조회 전에 거절하고, 응답은 공개 예제 순번부터 반환한다. */
    private List<Input> prepareInputs(
        RunRequest request,
        SubmissionJudgingDataResponse data,
        ExecutionCodeGenerator generator,
        Runnable guard
    )
        throws InterruptedException {
        List<Input> inputs = new ArrayList<>();
        if (request.customInputs() != null) {
            int index = 0;
            for (var custom : request.customInputs()) {
                try {
                    inputs
                        .add(
                            new Input(
                                null,
                                index++,
                                null,
                                generator.generate(data.functionSpec(), request.sourceCode(), custom.argumentTexts())
                            )
                        );
                } catch (IllegalArgumentException failure) {
                    throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
                }
            }
        }
        if (Boolean.TRUE.equals(request.useSamples())) {
            inputs.addAll(0, prepareSamples(request, data, generator, guard));
        }
        return inputs;
    }

    /** 공개 예제의 원문만 읽으며 원격 자료 오류를 사용자 입력 오류와 구분한다. */
    private List<Input> prepareSamples(
        RunRequest request,
        SubmissionJudgingDataResponse data,
        ExecutionCodeGenerator generator,
        Runnable guard
    )
        throws InterruptedException {
        List<Input> samples = new ArrayList<>();
        for (var test : data
            .cases()
            .stream()
            .filter(value -> Boolean.TRUE.equals(value.isSample()))
            .sorted(Comparator.comparingInt(TestCase::testOrder))
            .toList()) {
            guard.run();
            var content = assets.read(test.asset());
            if (content.expectedReturnText() == null) {
                throw unavailable();
            }
            try {
                samples
                    .add(
                        new Input(
                            test.testAssetId(),
                            null,
                            content.expectedReturnText(),
                            generator.generate(data.functionSpec(), request.sourceCode(), content.argumentTexts())
                        )
                    );
            } catch (IllegalArgumentException failure) {
                throw unavailable();
            }
        }
        return samples;
    }

    /** 실행은 한 번만 생성한다. 대기 중에는 같은 토큰을 조회하고 기한과 소유권을 다시 확인한다. */
    private JudgeTestResult executeCase(
        Input input,
        JudgeRuntime runtime,
        SubmissionJudgingDataResponse data,
        Runnable guard
    )
        throws InterruptedException {
        var limits = data.executionLimits();
        guard.run();
        String token;
        try {
            token
                = client
                    .create(input.source().toRequest(runtime, limits.timeMs(), limits.wallTimeMs(), limits.memoryKb()))
                    .token();
        } catch (JudgeException failure) {
            if (failure.getErrorCode().getStatus().is5xxServerError()) {
                throw unavailable();
            }
            throw failure;
        }
        while (true) {
            guard.run();
            JudgeExecutionResultResponse raw;
            try {
                raw = client.find(token);
            } catch (JudgeException failure) {
                throw unavailable();
            }
            var result
                = input.assetId() == null ? evaluator.evaluateCustom(raw, limits.memoryKb(), limits.outputLimitKb())
                    : evaluator
                        .evaluate(
                            raw,
                            data.functionSpec().returnType(),
                            input.expected(),
                            limits.memoryKb(),
                            limits.outputLimitKb()
                        );
            guard.run();
            if (result.state() == State.SYSTEM_ERROR) {
                throw unavailable();
            }
            if (result.state() == State.COMPLETED) {
                return result;
            }
            Thread.sleep(100);
        }
    }

    /** 원격 응답 원문이나 실행 코드를 오류에 포함하지 않는다. */
    private JudgeException unavailable() {
        return new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }

    /** 소유권과 요청 기한을 확인하면서 수행하는 실행이다. */
    private interface RunWork {
        /** 검증·실행 단계마다 기한과 소유권을 확인한다. */
        RunResponse run(Runnable guard) throws Exception;
    }

    /** 공개 예제 또는 직접 입력의 응답 식별자와 준비한 실행 자료다. */
    private record Input(String assetId, Integer index, String expected, ExecutionSource source) {
    }
}

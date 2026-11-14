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
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
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
    private final JudgeEngineCircuit circuit;

    /**
     * 작업을 선점해 DB 트랜잭션 밖에서 실행한다. 생성 응답이 불명확하면 재생성하지 않고 복구 대상으로 남긴다.
     * 실행 오류는 시스템 실패 처리로 넘기며, 인터럽트나 소유권 상실 시 추가 실행을 중단한다.
     *
     * @return 실행 성공 여부와 무관하게 작업을 선점했으면 true, 중단 상태이거나 선점 대상이 없으면 false
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean runOnce() {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        var permission = circuit.acquire();
        if (permission.isEmpty()) {
            return false;
        }
        try (var permit = permission.orElseThrow()) {
            return runPermitted(permit);
        }
    }

    /**
     * 서킷 허가를 가진 작업 한 건을 실행하고 실제 호출 직전 차단을 재확인한다.
     *
     * @param permit 현재 인스턴스의 호출 허가
     * @return 작업을 선점했는지 여부
     */
    private boolean runPermitted(JudgeEngineCircuit.Permit permit) {
        var claimed = store.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        var lease = claimed.orElseThrow();
        var heartbeat = new SubmissionHeartbeat(lease, store, clock);
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", lease.correlationId());
        String errorCode = "TEST_DATA_UNAVAILABLE";
        try {
            if (!heartbeat.active()) {
                return true;
            }
            var input = store.input(lease);
            var data = dataClient.find(input.problemId(), input.revisionId());
            if (input.runtime() == null) {
                if (!heartbeat.active()) {
                    return true;
                }
                var runtime = store.initializeExternal(lease, data, input.language());
                if (runtime.isEmpty()) {
                    return true;
                }
                input
                    = new SubmissionExecutionInput(
                        input.problemId(),
                        input.revisionId(),
                        input.language(),
                        input.sourceCode(),
                        runtime.orElseThrow()
                    );
            }
            store.validateCases(lease, data);
            languages.requireRuntime(input.language(), input.runtime());
            if (!heartbeat.active() || !store.prepare(lease)) {
                return true;
            }
            String language = input.language();
            var generator
                = generators
                    .stream()
                    .filter(value -> value.language().getCode().equals(language))
                    .findFirst()
                    .orElseThrow();
            var completedOrders = store.completedOrders(lease);
            var resumableTokens = store.resumableTokens(lease);
            for (var test : data
                .cases()
                .stream()
                .sorted(Comparator.comparingInt(SubmissionJudgingDataResponse.TestCase::testOrder))
                .toList()) {
                if (completedOrders.contains(test.testOrder())) {
                    continue;
                }
                errorCode = "TEST_DATA_UNAVAILABLE";
                if (!heartbeat.active()) {
                    return true;
                }
                var content = assets.read(test.asset());
                var source = generator.generate(data.functionSpec(), input.sourceCode(), content.argumentTexts());
                errorCode = "JUDGE0_UNAVAILABLE";
                var result
                    = execute(
                        heartbeat,
                        input,
                        data,
                        test,
                        content,
                        source,
                        resumableTokens.get(test.testOrder()),
                        permit
                    );
                if (result.isEmpty()) {
                    return true;
                }
                if (result.orElseThrow().verdict() == JudgeVerdict.SYSTEM_ERROR) {
                    store.fail(lease, errorCode);
                    return true;
                }
            }
            store.complete(lease, data);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // 원문·Judge0 응답은 로그나 오류 코드에 포함하지 않는다.
            log.warn("제출 채점 실패: jobId={}, type={}", lease.jobId(), failure.getClass().getSimpleName());
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

    /**
     * 생성된 코드를 실행하고 현재 임대의 테스트 결과만 저장한다.
     *
     * @param heartbeat 작업 전체의 임대 갱신기
     * @param input 고정 실행 환경과 제출 정보
     * @param data 고정 회차의 명세와 제한
     * @param test 실행할 테스트 메타데이터
     * @param content 비교할 테스트 원문
     * @param source 생성이 완료된 실행 코드
     * @param previousToken 회수된 작업에 저장된 Judge0 토큰, 새 실행이면 null
     * @param permit 현재 작업의 서킷 호출 허가
     * @return 저장한 결과, 소유권 상실 또는 생성 응답 불명이면 빈 값
     * @throws InterruptedException 결과 대기 중 실행 스레드가 중단된 경우
     */
    private Optional<JudgeTestResult> execute(
        SubmissionHeartbeat heartbeat,
        SubmissionExecutionInput input,
        SubmissionJudgingDataResponse data,
        SubmissionJudgingDataResponse.TestCase test,
        SubmissionJudgingDataResponse.TestContent content,
        ExecutionSource source,
        String previousToken,
        JudgeEngineCircuit.Permit permit
    )
        throws InterruptedException {
        var limits = data.executionLimits();
        var lease = heartbeat.lease();
        String token = previousToken;
        if (token == null) {
            token = create(heartbeat, input, data, test, source, permit);
            if (token == null) {
                return Optional.empty();
            }
        }
        String lookupToken = token;
        while (!Thread.currentThread().isInterrupted() && heartbeat.active()) {
            JudgeTestResult result;
            try {
                var response = permit.invoke(() -> client.find(lookupToken));
                if (response.isEmpty()) {
                    if (!permit.hasCalled()) {
                        store.releaseUncalled(lease);
                    }
                    return Optional.empty();
                }
                result
                    = evaluator
                        .evaluate(
                            response.orElseThrow(),
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
                return store.saveResult(lease, test.testOrder(), token, result) ? Optional.of(result)
                    : Optional.empty();
            }
            Thread.sleep(1000);
        }
        return Optional.empty();
    }

    /**
     * 임대가 유효하고 기존 토큰이 없는 테스트의 Judge0 실행을 생성한다.
     *
     * @param heartbeat 현재 작업의 임대 확인기
     * @param input 제출의 고정 실행 환경
     * @param data 실행 제한을 담은 채점 자료
     * @param test 실행할 테스트
     * @param source 생성할 프로그램과 표준 입력
     * @param permit 현재 작업의 서킷 호출 허가
     * @return 저장을 마친 Judge0 토큰, 생성이 확정되지 않으면 null
     */
    private String create(
        SubmissionHeartbeat heartbeat,
        SubmissionExecutionInput input,
        SubmissionJudgingDataResponse data,
        SubmissionJudgingDataResponse.TestCase test,
        ExecutionSource source,
        JudgeEngineCircuit.Permit permit
    ) {
        var limits = data.executionLimits();
        var lease = heartbeat.lease();
        if (!heartbeat.active()) {
            return null;
        }
        JudgeExecutionCreateResponse created;
        try {
            var response = permit.invoke(() -> {
                if (!store.beginCreate(lease, test.testOrder())) {
                    return null;
                }
                return client
                    .create(source.toRequest(input.runtime(), limits.timeMs(), limits.wallTimeMs(), limits.memoryKb()));
            });
            if (response.isEmpty()) {
                if (!permit.hasCalled()) {
                    store.releaseUncalled(lease);
                }
                return null;
            }
            created = response.orElseThrow();
        } catch (JudgeException failure) {
            Integer status = failure.getUpstreamStatus();
            if (status != null && status >= 400 && status < 500 && status != 408) {
                store.rejectCreate(lease, test.testOrder());
            }
            // 타임아웃·서버 오류·응답 유실은 생성 여부가 불명확하므로 UNKNOWN을 보존한다.
            return null;
        }
        if (created == null || !input.runtime().equals(created.runtime()) || created.token() == null
            || created.token().isBlank() || created.token().length() > 64
            || !store.saveToken(lease, test.testOrder(), created.token())) {
            return null;
        }
        return created.token();
    }

}

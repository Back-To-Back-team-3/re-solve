package com.backtoback.judge.judging.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.ValidationExecutionStore;

import lombok.RequiredArgsConstructor;

/** DB 트랜잭션 밖에서 테스트 한 건을 생성하고 같은 토큰으로 완료까지 조회한다. */
@Service
@RequiredArgsConstructor
public class ValidationTestExecutor {
    private final ValidationExecutionStore store;
    private final List<ExecutionCodeGenerator> generators;
    private final JudgeClient client;
    private final JudgeResultEvaluator evaluator;

    /**
     * 원문은 메모리에서만 사용하며 소유권을 잃거나 생성 결과가 불명확하면 중단한다.
     * 호출자가 전체 테스트 완료 후 작업 상태·시스템 실패 전이를 담당한다. 이 메서드는 트랜잭션을 열지 않는다.
     *
     * @param heartbeat 작업 전체에서 공유하는 임대 갱신 상태
     * @param runtime 고정 실행 환경
     * @param data 검증한 고정 원문
     * @param order 1부터 시작하는 테스트 순서
     * @return 저장한 완료 결과; 임대 만료 또는 생성 응답 유실이면 빈 값
     * @throws InterruptedException 종료 요청으로 대기를 중단한 경우
     * @throws IllegalArgumentException 함수 명세·입력·제한이 유효하지 않은 경우
     */
    public Optional<JudgeTestResult> execute(
        ValidationHeartbeat heartbeat,
        JudgeRuntime runtime,
        ProblemValidationDataResponse data,
        int order
    )
        throws InterruptedException {
        var lease = heartbeat.lease();
        if (Thread.currentThread().isInterrupted() || !heartbeat.active()) {
            return Optional.empty();
        }
        var test = data.testCases().get(order - 1);
        var generator
            = generators
                .stream()
                .filter(value -> value.language().getCode().equals(data.language()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하는 생성기가 없습니다."));
        var source = generator.generate(data.functionSpec(), data.sourceCode(), test.argumentTexts());
        var limits = data.executionLimits();
        if (!heartbeat.active() || !store.beginCreate(lease, order)) {
            return Optional.empty();
        }
        JudgeExecutionCreateResponse created;
        try {
            created = client.create(source.toRequest(runtime, limits.timeMs(), limits.wallTimeMs(), limits.memoryKb()));
        } catch (JudgeException failure) {
            // 생성됐을 가능성이 있으므로 UNKNOWN을 보존하고 자동 재생성하지 않는다.
            return Optional.empty();
        }
        if (created == null || !runtime.equals(created.runtime()) || created.token() == null
            || created.token().isBlank() || created.token().length() > 64
            || !store.saveToken(lease, order, created.token())) {
            return Optional.empty();
        }
        while (!Thread.currentThread().isInterrupted()) {
            if (!heartbeat.active()) {
                return Optional.empty();
            }
            JudgeExecutionResultResponse execution;
            try {
                execution = client.find(created.token());
            } catch (JudgeException failure) {
                // 조회 장애는 기존 token으로만 다시 확인하며 새 실행을 만들지 않는다.
                Thread.sleep(1000);
                continue;
            }
            var result
                = evaluator
                    .evaluate(
                        execution,
                        data.functionSpec().returnType(),
                        test.expectedReturnText(),
                        limits.memoryKb(),
                        limits.outputLimitKb()
                    );
            if (result.verdict() != null) {
                return store.saveResult(lease, order, result) ? Optional.of(result) : Optional.empty();
            }
            Thread.sleep(1000);
        }
        return Optional.empty();
    }
}

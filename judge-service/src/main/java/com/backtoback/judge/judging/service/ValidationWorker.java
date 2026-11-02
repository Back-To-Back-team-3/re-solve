package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.judge.judging.client.ProblemValidationDataClient;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.store.ValidationExecutionStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 작업 한 건의 자료 조회·고정 환경 선택·전체 테스트 실행·종결을 연결한다. 원격 호출 중 트랜잭션을 열지 않는다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationWorker {
    private final ValidationExecutionStore store;
    private final ProblemValidationDataClient dataClient;
    private final JudgeLanguageService languages;
    private final List<ExecutionCodeGenerator> generators;
    private final ValidationTestExecutor executor;
    private final Clock clock;

    /**
     * 작업 한 건을 선점해 모든 테스트를 실행한다. 일반 코드 실패로 나머지 테스트를 생략하지 않는다.
     * 불명확한 생성은 자동 재실행하지 않고, 시스템 실패는 저장소의 제한된 재시도 정책으로 전달한다.
     *
     * @return 선점한 작업이 있는지 여부
     * @throws org.springframework.dao.DataAccessException 선점 또는 실패 상태 저장이 불가능한 경우
     */
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
        var heartbeat = new ValidationHeartbeat(lease, store, clock);
        String previousTrace = MDC.get("correlationId");
        String runner = null;
        MDC.put("correlationId", lease.correlationId());
        try {
            if (!heartbeat.active()) {
                return true;
            }
            var data = dataClient.find(lease.request());
            if (!heartbeat.active()) {
                return true;
            }
            var runtime = languages.requireRuntime(data.language(), store.runtime(lease));
            if (!store.prepare(lease, data, runtime)) {
                return true;
            }
            var generator
                = generators
                    .stream()
                    .filter(value -> value.language().getCode().equals(data.language()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("지원하는 생성기가 없습니다."));
            runner = generator.runnerVersion();
            for (int order = 1; order <= data.testCases().size(); order++) {
                var result = executor.execute(heartbeat, runtime, data, order);
                if (result.isEmpty()) {
                    return true;
                }
                if (result.orElseThrow().verdict() == JudgeVerdict.SYSTEM_ERROR) {
                    store.fail(lease, runner);
                    return true;
                }
            }
            store.complete(lease, runner);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // 진행 중 token의 재생성은 금지하며 후속 복구가 소유권과 기한을 확인하도록 둔다.
        } catch (RuntimeException failure) {
            // 비동기 작업 경계에서 실패를 격리하되 원문·외부 응답·스택 추적은 저장하지 않는다.
            log
                .warn(
                    "검증 작업 실패: validationId={}, type={}",
                    lease.request().validationId(),
                    failure.getClass().getSimpleName()
                );
            store.fail(lease, runner);
        } finally {
            if (previousTrace == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previousTrace);
            }
        }
        return true;
    }
}

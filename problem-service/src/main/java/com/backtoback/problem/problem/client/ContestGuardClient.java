package com.backtoback.problem.problem.client;

import java.util.Map;
import java.util.Set;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemStateOperation;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 원격 가드 준비와 종결을 DB 트랜잭션 밖에서 호출한다. */
@RequiredArgsConstructor
public class ContestGuardClient {
    private static final Set<String> REJECTIONS
        = Set
            .of(
                "PROBLEM_IN_ACTIVE_EXAM",
                "CONTEST_OPERATION_BLOCKED",
                "SOURCE_VERSION_CONFLICT",
                "OPERATION_NOT_CURRENT",
                "IDEMPOTENCY_KEY_CONFLICT"
            );
    private final RestClient http;
    private final ObjectMapper mapper;
    private final CircuitBreaker circuit;

    /**
     * 같은 작업의 준비 또는 명세에 정의된 확정 거절을 조회한다.
     *
     * @param operation 원래 작업
     * @return 준비 성공이면 null, 확정 거절이면 원래 오류
     * @throws ProblemException 통신 실패·서킷 차단·불명확한 응답이면 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    public Rejection prepare(ProblemStateOperation operation) {
        try {
            return circuit.executeSupplier(() -> prepareRequest(operation));
        } catch (CallNotPermittedException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /**
     * 확정 업무 거절을 정상 결과로 분류하고 불명확한 실패만 서킷에 기록한다.
     *
     * @param operation 원래 작업
     * @return 준비 성공이면 null, 확정 거절이면 원래 오류
     * @throws ProblemException 원격 조회·응답 검증 실패 시 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    private Rejection prepareRequest(ProblemStateOperation operation) {
        try {
            var body
                = http
                    .post()
                    .uri(path(operation, "prepare"))
                    .body(
                        Map
                            .of(
                                "operationId",
                                operation.getOperationId(),
                                "operationType",
                                "PROBLEM_HIDE",
                                "expectedSourceVersion",
                                Integer.toString(operation.getExpectedSourceVersion())
                            )
                    )
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !operation.getOperationId().equals(body.path("operationId").asString())
                || !"PREPARED".equals(body.path("state").asString())
                || !"PENDING".equals(body.path("operationResult").asString()) || !body.path("ready").isBoolean()
                || !body.path("ready").asBoolean() || !body.path("canceledExamIds").isArray()
                || !body.path("canceledExamIds").isEmpty() || !body.path("blockingExamIds").isArray()
                || !body.path("blockingExamIds").isEmpty()) {
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return null;
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 409 || status == 422) {
                JsonNode body;
                try {
                    body = mapper.readTree(failure.getResponseBodyAsString());
                } catch (tools.jackson.core.JacksonException malformed) {
                    throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, malformed);
                }
                if (body == null || !body.isObject()) {
                    throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
                }
                var code = body.path("error").path("code").asString();
                if (code != null && REJECTIONS.contains(code)
                    && (status == 422) == "IDEMPOTENCY_KEY_CONFLICT".equals(code)) {
                    return new Rejection(status, failure.getResponseBodyAsString());
                }
            }
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        } catch (RestClientException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /**
     * 커밋된 결과를 같은 작업 ID로 전달한다.
     *
     * @param operation 종결 작업
     * @throws RestClientException 완료·취소 전달 실패
     * @throws CallNotPermittedException 서킷 차단으로 전달을 시도하지 못한 경우
     */
    public void finish(ProblemStateOperation operation) {
        circuit.executeRunnable(() -> finishRequest(operation));
    }

    /**
     * 확정된 작업의 완료 또는 취소를 전달한다.
     *
     * @param operation 종결 작업
     * @throws RestClientException 원격 전달 실패
     */
    private void finishRequest(ProblemStateOperation operation) {
        boolean committed = "COMMITTED".equals(operation.getState());
        http
            .post()
            .uri(path(operation, committed ? "complete" : "cancel"))
            .body(
                Map
                    .of(
                        "operationId",
                        operation.getOperationId(),
                        "sourceVersion",
                        Integer
                            .toString(
                                committed ? operation.getResultSourceVersion() : operation.getExpectedSourceVersion()
                            ),
                        "reason",
                        operation.getReason()
                    )
            )
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * 문제 가드 경로를 구성한다.
     *
     * @param operation 원래 작업
     * @param action 처리 단계
     * @return 가드 주소
     */
    private String path(ProblemStateOperation operation, String action) {
        return "/internal/v1/contest/guards/PROBLEM/" + operation.getProblemId() + "/" + action;
    }

    /** 확정된 업무 거절의 원래 응답이다. */
    public record Rejection(int status, String json) {
    }
}

package com.backtoback.problem.problem.client;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Judge의 내부 진행 조회 응답을 검증하고 의존 장애를 공통 오류로 변환한다. */
@Component
@RequiredArgsConstructor
public class JudgeProgressClient {
    private final RestClient http;
    private final ObjectMapper mapper;
    private final CircuitBreaker circuit;

    /**
     * 고정 회차의 검증 진행을 조회한다.
     *
     * @param id 검증 식별자
     * @param revisionId 원래 요청 회차
     * @param language 원래 요청 언어
     * @return Judge 상태 또는 작업 미생성 시 null
     * @throws ProblemException 회로 개방·통신 실패·응답 불일치 시 COMMON_DEPENDENCY_UNAVAILABLE
     */
    public Progress read(String id, String revisionId, String language) {
        try {
            return circuit.executeSupplier(() -> fetch(id, revisionId, language));
        } catch (CallNotPermittedException open) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Judge 응답의 요청 식별 정보를 확인한다.
     *
     * @param id 검증 식별자
     * @param revisionId 요청 회차
     * @param language 요청 언어
     * @return 진행 상태 또는 작업 미생성 시 null
     * @throws ProblemException 통신 실패·응답 불일치 시 COMMON_DEPENDENCY_UNAVAILABLE
     */
    private Progress fetch(String id, String revisionId, String language) {
        try {
            var result = http.get().uri("/internal/v1/judge/validations/{id}", id).retrieve().body(Progress.class);
            if (result == null || !id.equals(result.validationId()) || !revisionId.equals(result.problemRevisionId())
                || !language.equals(result.language()) || result.tests() == null
                || result.tests().stream().anyMatch(Objects::isNull) || result.status() == null
                || !Set
                    .of("QUEUED", "RUNNING", "RETRY_WAITING", "PASSED", "REJECTED", "ERROR")
                    .contains(result.status())) {
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return result;
        } catch (HttpClientErrorException.NotFound missing) {
            try {
                var error = mapper.readTree(missing.getResponseBodyAsByteArray());
                if (error != null && "PROBLEM_VALIDATION_NOT_FOUND".equals(error.at("/error/code").asString(""))) {
                    return null;
                }
            } catch (JacksonException invalid) {
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        } catch (RestClientException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Judge가 반환하는 현재 시도의 작업 상태와 판정 목록이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Progress(
        String validationId,
        String problemRevisionId,
        String language,
        String status,
        List<TestResult> tests,
        Integer maxTimeMs,
        String message
    ) {
    }
    /** Judge가 제공한 고정 순서의 테스트별 판정과 측정값이다. */
    public record TestResult(int testOrder, String verdict, Integer timeMs, Integer memoryKb) {
    }
}

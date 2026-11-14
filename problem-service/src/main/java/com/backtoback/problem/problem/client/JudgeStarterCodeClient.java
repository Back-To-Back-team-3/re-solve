package com.backtoback.problem.problem.client;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.response.StarterCodeResponse;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** Judge의 기본 코드 응답을 요청 언어 집합과 엄격히 대조한다. */
@Component
@RequiredArgsConstructor
public class JudgeStarterCodeClient {
    private final RestClient http;
    private final CircuitBreaker circuit;

    /**
     * 고정 함수 명세의 통과 언어별 기본 코드를 조회한다(API 명세서 §2.7).
     *
     * @param function 고정 함수 명세
     * @param languages 통과 언어
     * @return 요청 순서의 기본 코드
     * @throws ProblemException 통신 장애·서킷 차단·언어 누락·중복·잘못된 응답 시
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    public List<StarterCodeResponse> read(Map<String, Object> function, List<String> languages) {
        if (languages.isEmpty()) {
            return List.of();
        }
        try {
            return circuit.executeSupplier(() -> fetch(function, languages));
        } catch (CallNotPermittedException | RestClientException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /**
     * 문자열 원문과 언어 집합을 검사한다.
     *
     * @param function 고정 명세
     * @param languages 요청 언어
     * @return 기본 코드
     * @throws ProblemException 잘못된 원격 응답 시 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    private List<StarterCodeResponse> fetch(Map<String, Object> function, List<String> languages) {
        var root
            = http
                .post()
                .uri("/internal/v1/judge/starter-codes")
                .body(Map.of("functionSpec", function, "languages", languages))
                .retrieve()
                .body(JsonNode.class);
        var nodes = root == null ? null : root.get("starterCodes");
        Map<String, String> codes = new java.util.HashMap<>();
        if (nodes == null || !nodes.isArray()) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        for (var node : nodes) {
            var language = node.get("language");
            var source = node.get("sourceCode");
            if (language == null || !language.isString() || source == null || !source.isString()
                || codes.putIfAbsent(language.asString(), source.asString()) != null) {
                throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
        }
        if (!codes.keySet().equals(new HashSet<>(languages))) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        return languages.stream().map(language -> new StarterCodeResponse(language, codes.get(language))).toList();
    }
}

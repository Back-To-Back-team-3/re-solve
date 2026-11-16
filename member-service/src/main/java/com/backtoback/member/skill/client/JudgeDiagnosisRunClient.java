package com.backtoback.member.skill.client;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.skill.config.DiagnosisRunProperties;

import tools.jackson.databind.JsonNode;

/** 권한이 확인된 진단 Run을 Judge 공통 실행기에 전달한다(API 명세서 §1.5.9·§3.3.8). */
@Component
public class JudgeDiagnosisRunClient {
    private final RestClient client;

    /**
     * 조회용 2초 제한과 분리된 Run 전용 HTTP 클라이언트를 구성한다.
     *
     * @param builder 공통 RestClient 설정
     * @param properties Judge 내부 주소
     */
    @Autowired
    public JudgeDiagnosisRunClient(RestClient.Builder builder, DiagnosisRunProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.clone().baseUrl(properties.judgeServiceUrl()).requestFactory(factory).build();
    }

    /**
     * 테스트에서 HTTP 경계 대역을 주입한다.
     *
     * @param client 실행 요청 클라이언트
     */
    JudgeDiagnosisRunClient(RestClient client) {
        this.client = client;
    }

    /**
     * 진단 고정 회차를 실행하고 알려진 Run 오류와 원인 예외를 보존한다.
     *
     * @param request Member가 확인한 진단·회원·회차와 실행 코드
     * @return 결과를 저장하지 않는 동기 실행 응답
     * @throws MemberException 실행 제한·기한 초과·입력 오류 또는 의존 서비스 장애인 경우
     */
    public RunResult run(RunRequest request) {
        RunResult body;
        try {
            var response = client.post().uri("/internal/v1/judge/runs").headers(headers -> {
                String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                if (correlationId != null) {
                    headers.set(CorrelationIdFilter.HEADER, correlationId);
                }
            }).body(request).retrieve().toEntity(RunResult.class);
            body = response.getBody();
            if (response.getStatusCode() != HttpStatus.OK || !valid(body, request.customTests().size())) {
                throw new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
        } catch (RestClientResponseException failure) {
            throw remoteFailure(failure);
        } catch (RestClientException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                    throw new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
                }
                if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                    throw new MemberException(MemberErrorCode.RUN_TIMEOUT, failure);
                }
                cause = cause.getCause();
            }
            throw new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
        return body;
    }

    /**
     * 불완전하거나 요청 범위를 벗어난 실행 응답을 거절한다.
     *
     * @param result 원격 응답
     * @param customCount 요청한 직접 입력 개수
     * @return 응답 구조와 결과 순번이 유효하면 true
     */
    private boolean valid(RunResult result, int customCount) {
        return result != null
            && result.results() != null && result.completedAt()
                != null
            && result
                .results()
                .stream()
                .allMatch(
                    item -> item != null && item.index() >= 0
                        && ("SAMPLE".equals(item.type()) || "CUSTOM".equals(item.type()) && item.index() < customCount)
                        && item.stdout() != null && item.timeMs() != null && item.timeMs() >= 0
                        && item.memoryKb() != null && item.memoryKb() >= 0
                );
    }

    /**
     * 계약에 정의된 코드와 HTTP 상태가 일치할 때만 원격 오류를 전달한다.
     *
     * @param failure 원격 HTTP 오류
     * @return 원인 예외가 보존된 Member 오류
     */
    private MemberException remoteFailure(RestClientResponseException failure) {
        try {
            var body = failure.getResponseBodyAs(ApiErrorResponse.class);
            if (body != null && body.error() != null) {
                for (var code : List
                    .of(
                        MemberErrorCode.RUN_IN_PROGRESS,
                        MemberErrorCode.RUN_TIMEOUT,
                        MemberErrorCode.UNSUPPORTED_LANGUAGE,
                        MemberErrorCode.CODE_TOO_LARGE,
                        MemberErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE
                    )) {
                    if (code.getCode().equals(body.error().code())
                        && code.getStatus().value() == failure.getStatusCode().value()) {
                        return new MemberException(code, failure);
                    }
                }
                if (CommonErrorCode.COMMON_INVALID_REQUEST.getCode().equals(body.error().code())
                    && failure.getStatusCode() == HttpStatus.BAD_REQUEST) {
                    return new MemberException(CommonErrorCode.COMMON_INVALID_REQUEST, failure);
                }
            }
        } catch (RestClientException ignored) {
            // 잘못된 오류 본문도 최초 HTTP 예외를 원인으로 보존한다.
        }
        return new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
    }

    /** Member가 검증한 고정 회차의 내부 실행 요청이다. */
    public record RunRequest(
        String context,
        String contextId,
        String memberId,
        String problemId,
        String problemRevisionId,
        String language,
        String sourceCode,
        List<CustomTest> customTests
    ) {
    }

    /** 함수 매개변수 순서로 전달하는 인수다. */
    public record CustomTest(List<JsonNode> args) {
    }

    /** 공통 래퍼 없는 Judge 내부 실행 응답이다. */
    public record RunResult(List<TestResult> results, Instant completedAt) {
    }

    /** 실제 반환값과 디버그 출력을 분리한 실행 결과다. */
    public record TestResult(
        String type,
        int index,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS
        ) String output,
        String stdout,
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS
        ) String error,
        Integer timeMs,
        Integer memoryKb
    ) {
    }
}

package com.backtoback.contest.operation.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.contest.exam.client.ExamDependencyProperties;

/** 스터디와 문제의 원래 자원 작업 결과를 조회한다 (API 명세서 §4.8.9). */
@Component
public class ResourceOperationClient {
    private final RestClient study;
    private final RestClient problem;

    /** 두 소유 서비스에 같은 연결과 응답 시간 제한을 적용한다. */
    @Autowired
    public ResourceOperationClient(RestClient.Builder builder, ExamDependencyProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        study = builder.clone().baseUrl(properties.studyBaseUrl()).requestFactory(factory).build();
        problem = builder.clone().baseUrl(properties.problemBaseUrl()).requestFactory(factory).build();
    }

    ResourceOperationClient(RestClient study, RestClient problem) {
        this.study = study;
        this.problem = problem;
    }

    /** PENDING·404·조회 실패는 호출자가 가드를 유지하도록 전달한다. */
    public Result find(String type, long resourceId, String operationId) {
        RestClient target;
        String path;
        if ("STUDY".equals(type)) {
            target = study;
            path = "/internal/v1/studies/{resourceId}/operations/{operationId}";
        } else if ("PROBLEM".equals(type)) {
            target = problem;
            path = "/internal/v1/problems/{resourceId}/operations/{operationId}";
        } else {
            throw new IllegalArgumentException("Unsupported resource");
        }
        for (int attempt = 0;; attempt++) {
            try {
                var result = target.get().uri(path, resourceId, operationId).retrieve().body(Result.class);
                if (result == null || !operationId.equals(result.operationId()) || result.state() == null
                    || !Set.of("PENDING", "COMMITTED", "ABORTED").contains(result.state())
                    || result.sourceVersion() == null || !result.sourceVersion().matches("[0-9]+")
                    || result.resourceStatus() == null || result.resourceStatus().isBlank()) {
                    throw new IllegalStateException("Invalid resource operation response");
                }
                Long.parseLong(result.sourceVersion());
                return result;
            } catch (RuntimeException failure) {
                boolean retryable
                    = failure instanceof ResourceAccessException
                        || failure instanceof RestClientResponseException response
                            && (response.getStatusCode().value() == 502 || response.getStatusCode().value() == 503
                                || response.getStatusCode().value() == 504);
                if (!retryable || attempt == 2) {
                    throw failure;
                }
                try {
                    Thread.sleep(attempt == 0 ? 100 : 300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    /** 소유 서비스의 해당 작업에 고정된 결과다. */
    public record Result(String operationId, String state, String sourceVersion, String resourceStatus) {
    }
}

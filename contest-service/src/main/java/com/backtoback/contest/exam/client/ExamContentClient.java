package com.backtoback.contest.exam.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Problem의 고정 회차 본문과 이미지 직접 응답을 조회한다. */
@Component
public class ExamContentClient {
    private final RestClient client;

    /** 외부 호출의 연결과 응답 제한 시간을 구성한다. */
    @Autowired
    public ExamContentClient(RestClient.Builder builder, ExamDependencyProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        client = builder.clone().baseUrl(properties.problemBaseUrl()).requestFactory(factory).build();
    }

    /** 모의 HTTP 서버를 연결한다. */
    ExamContentClient(RestClient client) {
        this.client = client;
    }

    /** 인증된 시험 문맥에서 고정 회차 본문을 조회한다. */
    public Content content(long exam, long problem, long revision) {
        try {
            var content
                = retry(
                    () -> client
                        .get()
                        .uri("/internal/v1/problems/{p}/revisions/{r}/content", problem, revision)
                        .header("X-Access-Context", "EXAM:" + exam)
                        .retrieve()
                        .body(Content.class)
                );
            if (content == null || !Long.toString(problem).equals(content.problemId())
                || !Long.toString(revision).equals(content.problemRevisionId()) || content.description() == null
                || content.constraints() == null || content.examples() == null || content.functionSpec() == null
                || content.executionLimits() == null || content.samples() == null || content.title() == null
                || content.revisionNumber() < 1 || content.functionSpec().name() == null
                || content.functionSpec().parameters() == null || content.functionSpec().returnType() == null
                || content.executionLimits().timeMs() <= 0 || content.executionLimits().memoryKb() <= 0
                || content.executionLimits().wallTimeMs() <= 0 || content.executionLimits().outputLimitKb() <= 0
                || content
                    .samples()
                    .stream()
                    .anyMatch(
                        sample -> sample == null || sample.testAssetId() == null || sample.argumentTexts() == null
                            || sample.expectedReturnText() == null
                    )) {
                throw new IllegalStateException("Invalid fixed revision response");
            }
            return content;
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /** 고정 회차의 통과 언어를 원격 조회하며 누락된 응답은 장애로 처리한다. */
    public List<String> supportedLanguages(long exam, long problem, long revision) {
        try {
            var result
                = retry(
                    () -> client
                        .get()
                        .uri("/internal/v1/problems/{p}/revisions/{r}/content", problem, revision)
                        .header("X-Access-Context", "EXAM:" + exam)
                        .retrieve()
                        .body(LanguageContent.class)
                );
            if (result == null || !Long.toString(problem).equals(result.problemId())
                || !Long.toString(revision).equals(result.problemRevisionId()) || result.supportedLanguages() == null
                || result.supportedLanguages().isEmpty()
                || !Set.of("java", "python3", "cpp").containsAll(result.supportedLanguages())
                || result.supportedLanguages().stream().distinct().count() != result.supportedLanguages().size()) {
                throw new IllegalStateException("Invalid fixed revision languages");
            }
            return List.copyOf(result.supportedLanguages());
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /** 고정 회차 언어 확인에 필요한 응답 필드다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LanguageContent(String problemId, String problemRevisionId, List<String> supportedLanguages) {
    }

    /** Problem의 소속 검증을 거친 이미지와 MIME 타입을 반환한다. */
    public ResponseEntity<byte[]> image(long exam, long problem, long revision, long image) {
        try {
            var response
                = retry(
                    () -> client
                        .get()
                        .uri("/internal/v1/problems/{p}/revisions/{r}/images/{i}", problem, revision, image)
                        .header("X-Access-Context", "EXAM:" + exam)
                        .retrieve()
                        .toEntity(byte[].class)
                );
            MediaType type = response.getHeaders().getContentType();
            if (response.getBody() == null || type == null || !"image".equals(type.getType())) {
                throw new IllegalStateException("Invalid image response");
            }
            return response;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) {
                throw new ContestException(ContestErrorCode.PROBLEM_NOT_FOUND, failure);
            }
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /** 연결 장애와 일시적인 502·503·504만 두 차례 재시도한다. */
    private <T> T retry(Supplier<T> request) {
        for (int attempt = 0;; attempt++) {
            try {
                return request.get();
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

    /** 필요한 공개 회차 필드만 역직렬화한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String title,
        String description,
        String constraints,
        String examples,
        FunctionSpec functionSpec,
        ExecutionLimits executionLimits,
        List<Sample> samples
    ) {
    }

    /** 공개 예제의 입력과 기대 출력이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sample(String testAssetId, Map<String, String> argumentTexts, String expectedReturnText) {
    }

    /** 공개 함수 이름과 매개변수·반환 타입이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionSpec(String name, List<Parameter> parameters, TypeSpec returnType) {
    }

    /** 함수의 매개변수 이름과 타입이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameter(String name, TypeSpec type) {
    }

    /** 공통 타입 명세의 허용 필드만 재귀적으로 보존한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TypeSpec(
        String kind,
        TypeSpec elementType,
        List<TypeSpec> positionTypes,
        List<TypeSpec> repeatTypes,
        Map<String, TypeSpec> properties
    ) {
    }

    /** 고정 회차의 실행 제한이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionLimits(int timeMs, int memoryKb, int wallTimeMs, int outputLimitKb) {
    }
}

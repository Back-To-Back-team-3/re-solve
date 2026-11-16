package com.backtoback.member.skill.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.skill.config.SkillProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 진단에 쓰는 problem 내부 API. 출제 후보 조회(2.6.7)와 고정 회차 본문 조회(2.6.3)를 호출하고, 정확히 200이 아니면
 * {@code COMMON_DEPENDENCY_UNAVAILABLE}로 막는다(fail-closed).
 */
@Slf4j
@Component
public class ProblemDiagnosisClient {

    /**
     * 내부 API 공통 타임아웃 (API 명세서 1.8): 2초.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;

    @Autowired
    public ProblemDiagnosisClient(SkillProperties properties) {
        this(RestClient.builder().baseUrl(properties.problemServiceUrl()).requestFactory(requestFactory()).build());
    }

    ProblemDiagnosisClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 태그의 난이도별 출제 후보 (2.6.7). 후보가 없는 난이도는 {@code null}이다.
     */
    public List<Candidate> candidates(Long tagId) {
        Candidates body
            = fetch(
                () -> restClient
                    .get()
                    .uri(
                        uri -> uri.path("/internal/v1/problems/diagnosis-candidates").queryParam("tagId", tagId).build()
                    )
                    .retrieve()
                    .toEntity(Candidates.class)
            );
        if (body == null || body.problems() == null || !String.valueOf(tagId).equals(body.tagId())) {
            throw unavailable(null);
        }
        return body.problems();
    }

    /**
     * 진단에 고정한 회차의 본문 (2.6.3). 응답은 그대로 전달한다.
     */
    public JsonNode content(Long diagnosisId, Long problemId, Long problemRevisionId) {
        JsonNode body
            = fetch(
                () -> restClient
                    .get()
                    .uri(
                        "/internal/v1/problems/{problemId}/revisions/{revisionId}/content",
                        problemId,
                        problemRevisionId
                    )
                    .header("X-Access-Context", "DIAGNOSIS:" + diagnosisId)
                    .retrieve()
                    .toEntity(JsonNode.class)
            );
        if (body == null || !body.isObject()) {
            throw unavailable(null);
        }
        return body;
    }

    /**
     * 진단 고정 회차에 연결된 이미지를 조회한다(API 명세서 1.5.8·2.6.4).
     *
     * @param diagnosisId 권한이 확인된 진단 ID
     * @param problemId 출제 문항 ID
     * @param revisionId 진단에 고정된 회차 ID
     * @param imageId 본문 연결 이미지 ID
     * @return 실제 MIME과 이미지 바이너리
     * @throws MemberException 이미지가 없거나 의존 서비스의 응답을 신뢰할 수 없는 경우
     */
    public ResponseEntity<byte[]> image(Long diagnosisId, Long problemId, Long revisionId, Long imageId) {
        try {
            ResponseEntity<byte[]> response
                = restClient
                    .get()
                    .uri("/internal/v1/problems/{p}/revisions/{r}/images/{i}", problemId, revisionId, imageId)
                    .header("X-Access-Context", "DIAGNOSIS:" + diagnosisId)
                    .headers(headers -> {
                        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (correlationId != null) {
                            headers.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .retrieve()
                    .toEntity(byte[].class);
            MediaType type = response.getHeaders().getContentType();
            if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK) || response.getBody() == null
                || response.getBody().length == 0 || type == null
                || !List
                    .of(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.parseMediaType("image/webp"))
                    .contains(type)) {
                throw unavailable(null);
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND, exception);
            }
            throw unavailable(exception);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private static <T> T fetch(Supplier<ResponseEntity<T>> call) {
        ResponseEntity<T> response;
        try {
            response = call.get();
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
        if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK)) {
            throw unavailable(null);
        }
        return response.getBody();
    }

    /**
     * 원인을 보존한 의존 서비스 오류를 생성한다.
     *
     * @param cause 원격 호출 원인 또는 비정상 응답이면 {@code null}
     * @return 공통 의존 서비스 오류
     */
    private static MemberException unavailable(Throwable cause) {
        log
            .warn(
                "Problem diagnosis lookup failed: {}",
                cause == null ? "invalid response" : cause.getClass().getSimpleName()
            );
        return cause == null ? new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            : new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, cause);
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        return requestFactory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidates(String tagId, List<Candidate> problems) {
    }

    /**
     * 출제 후보 한 문항.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(int difficulty, String problemId, String problemRevisionId, String title) {
    }
}

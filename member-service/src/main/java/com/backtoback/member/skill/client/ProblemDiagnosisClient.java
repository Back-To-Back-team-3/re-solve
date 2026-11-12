package com.backtoback.member.skill.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
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

    private static MemberException unavailable(RestClientException cause) {
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

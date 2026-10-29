package com.backtoback.member.skill.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.skill.config.SkillProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * problem-service 태그 존재 확인 (API 명세서 2.6.11). 확인할 수 없으면 진입을 막는다(fail-closed).
 */
@Slf4j
@Component
public class ProblemTagClient {

    static final String TAG_NOT_FOUND = "PROBLEM_TAG_NOT_FOUND";

    /**
     * 내부 API 공통 타임아웃 (API 명세서 1.8): 2초.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;

    @Autowired
    public ProblemTagClient(SkillProperties properties) {
        this(
            RestClient.builder().baseUrl(properties.problemServiceUrl()).requestFactory(timeoutRequestFactory()).build()
        );
    }

    ProblemTagClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        return requestFactory;
    }

    /**
     * 200 본문의 {@code tagId}가 요청과 같으면 {@code true}, problem이 {@code PROBLEM_TAG_NOT_FOUND}로 응답하면 {@code false}를 반환한다.
     * 그 밖의 응답(본문이 다른 200, 204, 경로가 없는 404 포함)과 통신 오류는 {@code COMMON_DEPENDENCY_UNAVAILABLE}로 실패시킨다.
     */
    public boolean exists(Long tagId) {
        try {
            return restClient.get().uri("/internal/v1/tags/{tagId}", tagId).exchange((request, response) -> {
                if (response.getStatusCode().isSameCodeAs(HttpStatus.OK)) {
                    TagBody body = response.bodyTo(TagBody.class);
                    if (body != null && String.valueOf(tagId).equals(body.tagId())) {
                        return true;
                    }
                }
                if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    ErrorEnvelope body = response.bodyTo(ErrorEnvelope.class);
                    if (body != null && body.error() != null && TAG_NOT_FOUND.equals(body.error().code())) {
                        return false;
                    }
                }
                log.warn("Problem tag lookup failed: status={}", response.getStatusCode().value());
                throw new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            });
        } catch (RestClientException exception) {
            log.warn("Problem tag lookup failed: {}", exception.getClass().getSimpleName());
            throw new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TagBody(String tagId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorBody error) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorBody(String code) {
        }
    }
}

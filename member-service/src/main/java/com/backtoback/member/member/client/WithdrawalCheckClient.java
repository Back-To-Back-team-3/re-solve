package com.backtoback.member.member.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.config.MemberClientProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 탈퇴 차단 조건을 contest·study에 확인한다 (API 명세서 1.2.3). 확인할 수 없으면 탈퇴를 막는다(fail-closed).
 */
@Slf4j
@Component
public class WithdrawalCheckClient {

    /**
     * 내부 API 공통 타임아웃 (API 명세서 1.8): 2초.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient contestClient;
    private final RestClient studyClient;

    @Autowired
    public WithdrawalCheckClient(MemberClientProperties properties) {
        this(client(properties.contestServiceUrl()), client(properties.studyServiceUrl()));
    }

    WithdrawalCheckClient(RestClient contestClient, RestClient studyClient) {
        this.contestClient = contestClient;
        this.studyClient = studyClient;
    }

    /**
     * 실제 입장한 미확정 시험·대회가 있는지 (contest 4.7.2 {@code withdrawalBlocked}).
     */
    public boolean hasActiveExam(Long memberId) {
        ActiveParticipations body
            = fetch(
                "contest",
                () -> contestClient
                    .get()
                    .uri("/internal/v1/contest/members/{memberId}/active-participations", memberId)
                    .retrieve()
                    .body(ActiveParticipations.class)
            );
        if (body == null || body.withdrawalBlocked() == null) {
            throw unavailable("contest", null);
        }
        return body.withdrawalBlocked();
    }

    /**
     * {@code CLOSED}가 아닌 스터디의 스터디장인지 (study 5.7.2, {@code APPROVED} 가입만).
     */
    public boolean isStudyLeader(Long memberId) {
        LeaderMemberships body
            = fetch(
                "study",
                () -> studyClient
                    .get()
                    .uri(
                        uri -> uri
                            .path("/internal/v1/studies/memberships")
                            .queryParam("memberId", memberId)
                            .queryParam("role", "LEADER")
                            .queryParam("activeOnly", true)
                            .build()
                    )
                    .retrieve()
                    .body(LeaderMemberships.class)
            );
        if (body == null || body.items() == null || !String.valueOf(memberId).equals(body.memberId())) {
            throw unavailable("study", null);
        }
        return !body.items().isEmpty();
    }

    // 200이 아닌 응답(가입이 없는 회원의 404 포함)과 통신 오류는 확인 실패로 본다.
    private static <T> T fetch(String service, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException exception) {
            throw unavailable(service, exception);
        }
    }

    private static MemberException unavailable(String service, RestClientException cause) {
        log
            .warn(
                "Withdrawal check failed: service={}, cause={}",
                service,
                cause == null ? "invalid body" : cause.getClass().getSimpleName()
            );
        return cause == null ? new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            : new MemberException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, cause);
    }

    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActiveParticipations(Boolean withdrawalBlocked) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LeaderMemberships(String memberId, List<Object> items) {
    }
}

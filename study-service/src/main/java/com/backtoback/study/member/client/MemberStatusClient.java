package com.backtoback.study.member.client;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.member.config.MemberClientProperties;
import com.backtoback.study.replica.domain.MemberStatus;

/**
 * member 서비스 내부 상태 조회 클라이언트 (API 명세서 1.7.1). 재시도하지 않고 실패는 fail-closed로 처리한다
 * (API 명세서 부록 A 호출 측 fail-closed).
 */
@Component
public class MemberStatusClient {

    private final RestClient member;

    @Autowired
    public MemberStatusClient(RestClient.Builder builder, MemberClientProperties properties) {
        var http
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        member = builder.clone().baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
    }

    /** 테스트에서 미리 구성된 RestClient를 직접 주입할 때 사용한다. */
    MemberStatusClient(RestClient member) {
        this.member = member;
    }

    /**
     * 회원의 현재 상태를 조회한다. DB 연결을 잡은 채 원격 응답을 기다리지 않도록 트랜잭션 밖에서만 호출한다.
     *
     * @throws StudyException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 통신 실패, 오류 응답, 빈 본문, 다른 회원 응답, 계약 밖 상태 값인 경우
     * @throws IllegalStateException 트랜잭션 안에서 호출한 경우
     */
    public MemberStatus fetchStatus(long memberId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Member status lookup must occur outside a transaction");
        }
        try {
            MemberStatusResponse response
                = member
                    .get()
                    .uri("/internal/v1/members/{memberId}/status", memberId)
                    .retrieve()
                    .body(MemberStatusResponse.class);
            if (response == null) {
                throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return response.validatedStatus(memberId);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, exception);
        }
    }
}

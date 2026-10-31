package com.backtoback.judge.submission.client;

import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.config.SubmissionClientProperties;
import com.backtoback.judge.submission.dto.MemberStatusResponse;
import com.backtoback.judge.submission.dto.MemberTagLevelsResponse;
import com.backtoback.judge.submission.dto.ProblemJudgingDataResponse;
import com.backtoback.judge.submission.dto.ProblemValidationResponse;

/** 고정 회차와 회원 검증 응답을 조회하며 장애 시 접수를 허용하지 않는다. (API 명세서 §3.1.2) */
@Component
public class SubmissionDependencyClient {
    private final RestClient problem;
    private final RestClient member;

    /**
     * 공통 상관관계 ID 전파를 유지하는 제한 시간 기반 클라이언트를 생성한다.
     *
     * @param builder 공통 설정이 적용된 빌더
     * @param properties 내부 주소·제한 시간
     */
    public SubmissionDependencyClient(RestClient.Builder builder, SubmissionClientProperties properties) {
        var http
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        problem = builder.clone().baseUrl(properties.problemBaseUrl().toString()).requestFactory(factory).build();
        member = builder.clone().baseUrl(properties.memberBaseUrl().toString()).requestFactory(factory).build();
    }

    /**
     * 고정 문제 회차의 제출 가능 조건을 조회한다.
     *
     * @param problemId 문제 ID
     * @param revisionId 고정 회차 ID
     * @return 내부 사전 검증 응답
     * @throws JudgeException {@code PROBLEM_NOT_AVAILABLE}: 제출할 수 없거나 존재하지 않는 문제인 경우;
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 조회 실패 또는 응답 계약 불일치인 경우
     */
    public ProblemValidationResponse validation(long problemId, long revisionId) {
        return get(
            problem,
            "/internal/v1/problems/" + problemId + "/revisions/" + revisionId + "/judging/validation",
            ProblemValidationResponse.class,
            true
        );
    }

    /**
     * 원문을 읽지 않고 고정 회차 테스트의 순번·공개 여부를 조회한다.
     *
     * @param problemId 문제 ID
     * @param revisionId 고정 회차 ID
     * @return 고정 테스트 메타데이터
     * @throws JudgeException {@code PROBLEM_NOT_AVAILABLE}: 제출할 수 없거나 존재하지 않는 문제인 경우;
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 조회 실패 또는 응답 계약 불일치인 경우
     */
    public ProblemJudgingDataResponse data(long problemId, long revisionId) {
        return get(
            problem,
            "/internal/v1/problems/" + problemId + "/revisions/" + revisionId + "/judging/data",
            ProblemJudgingDataResponse.class,
            true
        );
    }

    /**
     * 복제본이 없는 회원의 최신 상태를 조회한다.
     *
     * @param memberId 인증 회원 ID
     * @return 회원 상태 응답
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 조회가 실패한 경우
     */
    public MemberStatusResponse status(long memberId) {
        return get(member, "/internal/v1/members/" + memberId + "/status", MemberStatusResponse.class, false);
    }

    /**
     * 진입한 태그의 회원 레벨을 조회한다.
     *
     * @param memberId 인증 회원 ID
     * @return 태그 레벨 응답
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 조회가 실패한 경우
     */
    public MemberTagLevelsResponse levels(long memberId) {
        return get(member, "/internal/v1/members/" + memberId + "/tag-levels", MemberTagLevelsResponse.class, false);
    }

    /**
     * 조회 응답과 장애를 공통 계약으로 변환한다. 외부 본문을 오류로 노출하지 않는다.
     *
     * @param <T> 응답 타입
     * @param client 대상 클라이언트
     * @param path 내부 경로
     * @param type 응답 클래스
     * @param problemRequest Problem 404를 제출 불가로 변환할지 여부
     * @return null이 아닌 응답
     * @throws JudgeException {@code PROBLEM_NOT_AVAILABLE}: Problem이 대상 미존재를 반환한 경우;
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 조회 실패 또는 본문 변환 실패인 경우
     */
    private <T> T get(RestClient client, String path, Class<T> type, boolean problemRequest) {
        try {
            T response = client.get().uri(path).retrieve().body(type);
            if (response == null) {
                throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (problemRequest && exception.getStatusCode().value() == 404) {
                throw new JudgeException(JudgeErrorCode.PROBLEM_NOT_AVAILABLE);
            }
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

}

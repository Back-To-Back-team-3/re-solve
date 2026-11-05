package com.backtoback.contest.exam.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/**
 * 시험 설정에 필요한 스터디와 문제 스냅샷을 트랜잭션 밖에서 조회한다.
 * API 명세서 v1.1 §2.6.5, §4.8 및 §5.7.1의 직접 응답을 사용한다.
 */
@Component
public class ExamDependencyClient {
    private final RestClient study;
    private final RestClient problem;

    /** 내부 조회의 연결 및 응답 제한 시간을 각각 2초로 구성한다. */
    @Autowired
    public ExamDependencyClient(RestClient.Builder builder, ExamDependencyProperties properties) {
        var factory
            = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.study = builder.clone().baseUrl(properties.studyBaseUrl()).requestFactory(factory).build();
        this.problem = builder.clone().baseUrl(properties.problemBaseUrl()).requestFactory(factory).build();
    }

    /** 모의 HTTP 서버를 연결한다. */
    ExamDependencyClient(RestClient study, RestClient problem) {
        this.study = study;
        this.problem = problem;
    }

    /** 스터디 상태와 현재 구성원 역할을 읽는다. */
    public StudyMembers study(long studyId) {
        var result
            = fetch(
                () -> study.get().uri("/internal/v1/studies/{id}/members", studyId).retrieve().body(StudyMembers.class)
            );
        if (result == null || !Long.toString(studyId).equals(result.studyId()) || result.studyStatus() == null
            || result.members() == null
            || result
                .members()
                .stream()
                .anyMatch(
                    member -> member == null || member.memberId() == null || member.role() == null
                        || member.membershipId() == null
                )) {
            throw unavailable(new IllegalArgumentException("Invalid study response"));
        }
        return result;
    }

    /** 요청한 문제의 선택 스냅샷을 읽는다. */
    public ProblemSummaries problems(List<Long> ids) {
        var result = fetch(() -> problem.get().uri(uri -> {
            var query = uri.path("/internal/v1/problems/summaries").queryParam("purpose", "EXAM");
            ids.forEach(id -> query.queryParam("ids", id));
            return query.build();
        }).retrieve().body(ProblemSummaries.class));
        if (result == null || result.items() == null
            || result
                .items()
                .stream()
                .anyMatch(item -> item == null || item.problemId() == null || item.isAvailable() == null)) {
            throw unavailable(new IllegalArgumentException("Invalid problem response"));
        }
        return result;
    }

    /** 연결 실패와 게이트웨이 일시 오류만 두 차례 재시도한다. */
    private <T> T fetch(Supplier<T> request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Dependency lookup must occur outside a transaction");
        }
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
                    throw unavailable(failure);
                }
                try {
                    Thread.sleep(attempt == 0 ? 100 : 300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable(interrupted);
                }
            }
        }
    }

    private ContestException unavailable(Throwable cause) {
        return new ContestException(ContestErrorCode.CONTEST_DEPENDENCY_UNAVAILABLE, cause);
    }

    /** 스터디 상태와 구성원 목록이다. */
    public record StudyMembers(String studyId, String studyStatus, List<StudyMember> members) {
    }

    /** 현재 스터디 구성원의 가입과 역할이다. */
    public record StudyMember(String memberId, String membershipId, String role) {
    }

    /** 요청한 문제 목록에 대한 직접 응답이다. */
    public record ProblemSummaries(List<ProblemSummary> items) {
    }

    /** 선택 시점의 문제 상태와 사용 회차다. */
    public record ProblemSummary(
        String problemId,
        Boolean isAvailable,
        String scope,
        String visibility,
        Long sourceVersion,
        String problemRevisionId,
        Integer revisionNumber,
        String title,
        List<String> supportedLanguages
    ) {
    }
}

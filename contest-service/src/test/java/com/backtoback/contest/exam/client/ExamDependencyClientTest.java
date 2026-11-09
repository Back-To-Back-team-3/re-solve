package com.backtoback.contest.exam.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 내부 조회의 직접 응답과 재시도 대상을 확인한다. */
class ExamDependencyClientTest {
    @Test
    @DisplayName("스터디 직접 응답에서 상태와 구성원 역할을 읽는다")
    void readsStudyMembers() {
        var builder = RestClient.builder().baseUrl("http://study-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://study-test/internal/v1/studies/12/members")).andRespond(withSuccess("""
            {"studyId":"12","studyStatus":"ACTIVE","members":[{"memberId":"7","membershipId":"8","role":"LEADER"}]}
            """, MediaType.APPLICATION_JSON));
        var client = new ExamDependencyClient(builder.build(), RestClient.create());
        assertThat(client.study(12).members().getFirst().role()).isEqualTo("LEADER");
        server.verify();
    }

    @Test
    @DisplayName("문제 조회는 EXAM 용도와 반복 ID를 전달한다")
    void readsProblems() {
        var builder = RestClient.builder().baseUrl("http://problem-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://problem-test/internal/v1/problems/summaries?purpose=EXAM&ids=1&ids=2"))
            .andRespond(withSuccess("""
                {"items":[{"problemId":"1","isAvailable":true},{"problemId":"2","isAvailable":false}]}
                """, MediaType.APPLICATION_JSON));
        var client = new ExamDependencyClient(RestClient.create(), builder.build());
        assertThat(client.problems(List.of(1L, 2L)).items()).extracting("isAvailable").containsExactly(true, false);
        server.verify();
    }

    @Test
    @DisplayName("503은 두 번 재시도한 뒤 성공 응답을 사용한다")
    void retriesUnavailable() {
        var builder = RestClient.builder().baseUrl("http://study-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            server
                .expect(requestTo("http://study-test/internal/v1/studies/12/members"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        server
            .expect(requestTo("http://study-test/internal/v1/studies/12/members"))
            .andRespond(
                withSuccess(
                    "{\"studyId\":\"12\",\"studyStatus\":\"ACTIVE\",\"members\":[]}",
                    MediaType.APPLICATION_JSON
                )
            );
        assertThat(new ExamDependencyClient(builder.build(), RestClient.create()).study(12).studyStatus())
            .isEqualTo("ACTIVE");
        server.verify();
    }

    @Test
    @DisplayName("400은 재시도하지 않고 원인을 보존한 의존 장애로 변환한다")
    void doesNotRetryBadRequest() {
        var builder = RestClient.builder().baseUrl("http://study-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://study-test/internal/v1/studies/12/members"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> new ExamDependencyClient(builder.build(), RestClient.create()).study(12))
            .isInstanceOf(ContestException.class)
            .hasCauseInstanceOf(RuntimeException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.CONTEST_DEPENDENCY_UNAVAILABLE)
            );
        server.verify();
    }
}

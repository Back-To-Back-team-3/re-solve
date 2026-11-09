package com.backtoback.member.member.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;

class WithdrawalCheckClientTest {

    private static final String CONTEST_URL
        = "http://contest-service/internal/v1/contest/members/7/active-participations";
    private static final String STUDY_URL
        = "http://study-service/internal/v1/studies/memberships?memberId=7&role=LEADER&activeOnly=true";

    private MockRestServiceServer contest;
    private MockRestServiceServer study;
    private WithdrawalCheckClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder contestBuilder = RestClient.builder().baseUrl("http://contest-service");
        RestClient.Builder studyBuilder = RestClient.builder().baseUrl("http://study-service");
        contest = MockRestServiceServer.bindTo(contestBuilder).build();
        study = MockRestServiceServer.bindTo(studyBuilder).build();
        client = new WithdrawalCheckClient(contestBuilder.build(), studyBuilder.build());
    }

    @Test
    @DisplayName("contest 4.7.2의 withdrawalBlocked를 그대로 쓴다")
    void readsActiveExam() {
        contest
            .expect(requestTo(CONTEST_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"withdrawalBlocked\":true,\"items\":[{}]}", MediaType.APPLICATION_JSON));
        contest
            .expect(requestTo(CONTEST_URL))
            .andRespond(withSuccess("{\"withdrawalBlocked\":false,\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.hasActiveExam(7L)).isTrue();
        assertThat(client.hasActiveExam(7L)).isFalse();
    }

    @Test
    @DisplayName("study 5.7.2에 LEADER·activeOnly로 묻고, items가 있으면 스터디장이다")
    void readsStudyLeader() {
        study
            .expect(requestTo(STUDY_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    "{\"memberId\":\"7\",\"items\":[{\"studyId\":\"12\",\"role\":\"LEADER\"}]}",
                    MediaType.APPLICATION_JSON
                )
            );
        study
            .expect(requestTo(STUDY_URL))
            .andRespond(withSuccess("{\"memberId\":\"7\",\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.isStudyLeader(7L)).isTrue();
        assertThat(client.isStudyLeader(7L)).isFalse();
    }

    @Test
    @DisplayName("필드 누락·다른 회원 응답·404·서버 오류·통신 오류는 차단 없음이 아니라 COMMON_DEPENDENCY_UNAVAILABLE")
    void failsClosed() {
        contest.expect(requestTo(CONTEST_URL)).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        contest.expect(requestTo(CONTEST_URL)).andRespond(withServerError());
        study
            .expect(requestTo(STUDY_URL))
            .andRespond(withSuccess("{\"memberId\":\"8\",\"items\":[]}", MediaType.APPLICATION_JSON));
        study.expect(requestTo(STUDY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        study.expect(requestTo(STUDY_URL)).andRespond(withException(new IOException("connection refused")));

        for (int i = 0; i < 2; i++) {
            assertUnavailable(() -> client.hasActiveExam(7L));
        }
        for (int i = 0; i < 3; i++) {
            assertUnavailable(() -> client.isStudyLeader(7L));
        }
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }
}

package com.backtoback.member.skill.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

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
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;

class ProblemDiagnosisClientTest {

    private static final String CANDIDATES_URL
        = "http://problem-service/internal/v1/problems/diagnosis-candidates?tagId=3";
    private static final String CONTENT_URL = "http://problem-service/internal/v1/problems/301/revisions/1001/content";

    private MockRestServiceServer server;
    private ProblemDiagnosisClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://problem-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProblemDiagnosisClient(builder.build());
    }

    @Test
    @DisplayName("출제 후보(2.6.7)를 읽고, 후보가 없는 난이도의 null 항목도 그대로 넘긴다")
    void readsCandidates() {
        server
            .expect(requestTo(CANDIDATES_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    "{\"tagId\":\"3\",\"problems\":[{\"difficulty\":1,\"problemId\":\"301\","
                        + "\"problemRevisionId\":\"1001\"," + "\"title\":\"두 수의 합\"},null]}",
                    MediaType.APPLICATION_JSON
                )
            );

        List<Candidate> candidates = client.candidates(3L);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).isEqualTo(new Candidate(1, "301", "1001", "두 수의 합"));
        assertThat(candidates.get(1)).isNull();
    }

    @Test
    @DisplayName("고정 회차 본문(2.6.3)은 DIAGNOSIS 문맥 헤더로 조회하고 응답을 그대로 준다")
    void readsContent() {
        server
            .expect(requestTo(CONTENT_URL))
            .andExpect(header("X-Access-Context", "DIAGNOSIS:120"))
            .andRespond(withSuccess("{\"problemId\":\"301\",\"title\":\"두 수의 합\"}", MediaType.APPLICATION_JSON));

        assertThat(client.content(120L, 301L, 1001L).get("title").asString()).isEqualTo("두 수의 합");
    }

    @Test
    @DisplayName("다른 태그 응답·200이 아닌 성공(202)·서버 오류는 COMMON_DEPENDENCY_UNAVAILABLE")
    void failsClosed() {
        server
            .expect(requestTo(CANDIDATES_URL))
            .andRespond(withSuccess("{\"tagId\":\"4\",\"problems\":[]}", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo(CANDIDATES_URL))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"tagId\":\"3\",\"problems\":[]}")
            );
        server.expect(requestTo(CONTENT_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.candidates(3L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.candidates(3L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.content(120L, 301L, 1001L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }
}

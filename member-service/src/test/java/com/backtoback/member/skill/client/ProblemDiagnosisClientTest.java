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
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.member.global.error.MemberErrorCode;
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

        assertUnavailable(() -> client.candidates(3L));
        assertUnavailable(() -> client.candidates(3L));
        assertUnavailable(() -> client.content(120L, 301L, 1001L));
    }

    /** 고정 회차와 진단 권한 문맥으로 이미지 바이너리를 요청하는지 확인한다. */
    @Test
    @DisplayName("진단 이미지 요청은 고정 회차와 DIAGNOSIS 헤더를 사용하고 PNG를 보존한다")
    void readsImageWithDiagnosisContext() {
        byte[] bytes
            = {
                1,
                2,
                3
            };
        server
            .expect(requestTo("http://problem-service/internal/v1/problems/301/revisions/1001/images/701"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Access-Context", "DIAGNOSIS:120"))
            .andExpect(header("X-Correlation-Id", "diagnosis-image-test"))
            .andRespond(withSuccess(bytes, MediaType.IMAGE_PNG));

        try (var ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, "diagnosis-image-test")) {
            var image = client.image(120L, 301L, 1001L, 701L);
            assertThat(image.getBody()).containsExactly(bytes);
            assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        }
        server.verify();
    }

    /** 이미지 소속 불일치와 전송 장애를 서로 다른 외부 오류로 처리하는지 확인한다. */
    @Test
    @DisplayName("이미지 미발견은 진단 404로 변환하고 서버 장애는 원인을 보존한 503으로 처리한다")
    void imageFailureMapping() {
        String url = "http://problem-service/internal/v1/problems/301/revisions/1001/images/701";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(url)).andRespond(withServerError());

        assertThatThrownBy(() -> client.image(120L, 301L, 1001L, 701L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.DIAGNOSIS_NOT_FOUND);
        assertThatThrownBy(() -> client.image(120L, 301L, 1001L, 701L))
            .isInstanceOf(BusinessException.class)
            .hasCauseInstanceOf(org.springframework.web.client.RestClientException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }

    /** 비정상 성공 응답을 브라우저에 이미지로 전달하지 않는지 확인한다. */
    @Test
    @DisplayName("이미지 MIME이 아니거나 빈 본문 또는 202 응답이면 503으로 차단한다")
    void invalidImageFailsClosed() {
        String url = "http://problem-service/internal/v1/problems/301/revisions/1001/images/701";
        server.expect(requestTo(url)).andRespond(withSuccess("not an image", MediaType.TEXT_HTML));
        server.expect(requestTo(url)).andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));
        server
            .expect(requestTo(url))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED)
                    .body(
                        new byte[] {
                            1
                        }
                    )
                    .contentType(MediaType.IMAGE_PNG)
            );

        for (int index = 0; index < 3; index++) {
            assertUnavailable(() -> client.image(120L, 301L, 1001L, 701L));
        }
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }
}

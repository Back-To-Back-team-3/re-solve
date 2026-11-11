package com.backtoback.study.admin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** judge-service AC 이력 클라이언트의 역직렬화와 커서 순회 흐름을 검증한다. */
class JudgeAcHistoryClientTest {

    private JudgeAcHistoryClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://judge-test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new JudgeAcHistoryClient(builder.build());
    }

    /**
     * §3.3.4 응답의 4개 필드와 OffsetDateTime 접수 시각 파싱을 확인한다.
     */
    @Test
    @DisplayName("단일 페이지 응답의 항목 4필드와 접수 시각(OffsetDateTime)을 올바르게 역직렬화한다")
    void deserializesSinglePage() {
        server.expect(requestTo(containsString("/internal/v1/submissions/accepted"))).andRespond(withSuccess("""
            {
                "items": [{
                    "submissionId": "91023",
                    "problemId": "301",
                    "submittedAt": "2026-09-28T10:00:00+09:00",
                    "judgeAttempt": 2
                }],
                "nextCursor": null
            }
            """, MediaType.APPLICATION_JSON));

        var page = client.fetchPage(7L, null, 10);

        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).hasSize(1);
        var item = page.items().getFirst();
        assertThat(item.submissionId()).isEqualTo("91023");
        assertThat(item.problemId()).isEqualTo("301");
        assertThat(item.submittedAt()).isEqualTo(OffsetDateTime.parse("2026-09-28T10:00:00+09:00"));
        assertThat(item.judgeAttempt()).isEqualTo(2);
    }

    /**
     * 첫 페이지의 nextCursor 값이 두 번째 요청의 after 파라미터로 전달되는지 확인한다.
     */
    @Test
    @DisplayName("첫 페이지 커서로 두 번째 페이지를 조회할 때 after 파라미터가 전달된다")
    void traversesTwoPagesWithCursor() {
        server.expect(requestTo(not(containsString("after=")))).andRespond(withSuccess("""
            {
                "items": [{"submissionId": "100", "problemId": "1",
                    "submittedAt": "2026-09-28T10:00:00+09:00", "judgeAttempt": 1}],
                "nextCursor": "100"
            }
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("after=100"))).andRespond(withSuccess("""
            {
                "items": [{"submissionId": "200", "problemId": "2",
                    "submittedAt": "2026-10-01T09:00:00+09:00", "judgeAttempt": 1}],
                "nextCursor": null
            }
            """, MediaType.APPLICATION_JSON));

        var page1 = client.fetchPage(7L, null, 5);
        assertThat(page1.nextCursor()).isEqualTo("100");

        var page2 = client.fetchPage(7L, page1.nextCursor(), 5);
        assertThat(page2.nextCursor()).isNull();
        assertThat(page2.items().getFirst().submissionId()).isEqualTo("200");

        server.verify();
    }
}

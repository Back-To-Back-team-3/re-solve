package com.backtoback.contest.exam.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 고정 회차 호출의 문맥 헤더·재시도와 이미지 실패를 확인한다. */
class ExamContentClientTest {
    /** 일시 장애를 재시도할 때도 같은 시험·회차 문맥을 전달한다. */
    @Test
    @DisplayName("고정 본문 조회의 503은 재시도하고 EXAM 문맥을 전달한다")
    void retriesContent() {
        // given
        var builder = RestClient.builder().baseUrl("http://problem-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        String uri = "http://problem-test/internal/v1/problems/101/revisions/1001/content";
        server
            .expect(requestTo(uri))
            .andExpect(header("X-Access-Context", "EXAM:55"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(uri)).andExpect(header("X-Access-Context", "EXAM:55")).andRespond(withSuccess("""
            {"problemId":"101","problemRevisionId":"1001","revisionNumber":1,"title":"제목",
             "description":"본문","constraints":"제한","examples":"예시",
             "functionSpec":{"name":"solution","parameters":[],"returnType":{"kind":"INT32"}},
             "executionLimits":{"timeMs":1000,"memoryKb":1024,"wallTimeMs":6000,"outputLimitKb":64},
             "samples":[],"hiddenTests":["secret"]}
            """, MediaType.APPLICATION_JSON));
        var client = new ExamContentClient(builder.build());
        // when
        var result = client.content(55, 101, 1001);
        // then
        assertThat(result.problemRevisionId()).isEqualTo("1001");
        assertThat(result.samples()).isEmpty();
        server.verify();
    }

    /** 이미지 소속 불일치는 재시도하지 않고 404로 전달한다. */
    @Test
    @DisplayName("고정 회차에 없는 이미지의 404는 문제 없음으로 반환한다")
    void imageNotFound() {
        // given
        var builder = RestClient.builder().baseUrl("http://problem-test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("http://problem-test/internal/v1/problems/101/revisions/1001/images/701"))
            .andExpect(header("X-Access-Context", "EXAM:55"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        var client = new ExamContentClient(builder.build());
        // when & then
        assertThatThrownBy(() -> client.image(55, 101, 1001, 701))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.PROBLEM_NOT_FOUND)
            );
        server.verify();
    }
}

package com.backtoback.problem.problem.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemStateOperation;
import com.backtoback.problem.problem.domain.ProblemVisibility;

import tools.jackson.databind.json.JsonMapper;

/** 준비 응답의 식별자·상태와 확정 거절을 검증한다. */
class ContestGuardClientTest {
    /** 준비 성공과 확정 거절을 불명확한 응답과 구별한다. */
    @Test
    @DisplayName("같은 작업의 PREPARED만 수락하고 확정 업무 오류만 거절로 반환한다")
    void preparationContract() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var path = "/internal/v1/contest/guards/PROBLEM/1/prepare";
        server.expect(requestTo(path)).andRespond(withSuccess("""
            {"operationId":"op","state":"PREPARED","operationResult":"PENDING","ready":true,
             "canceledExamIds":[],"blockingExamIds":[]}
            """, MediaType.APPLICATION_JSON));
        server
            .expect(requestTo(path))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"error\":{\"code\":\"PROBLEM_IN_ACTIVE_EXAM\"}}")
            );
        server.expect(requestTo(path)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(path)).andRespond(withSuccess("""
            {"operationId":"other","state":"PREPARED","operationResult":"PENDING","ready":true,
             "canceledExamIds":[],"blockingExamIds":[]}
            """, MediaType.APPLICATION_JSON));
        var client = new ContestGuardClient(builder.build(), JsonMapper.builder().build());
        var problem = Problem.create(1L, ProblemScope.GENERAL);
        ReflectionTestUtils.setField(problem, "id", 1L);
        var operation
            = ProblemStateOperation.hide("op", problem, "key", "hash", 1, ProblemVisibility.ARCHIVED, "reason");
        assertThat(client.prepare(operation)).isNull();
        assertThat(client.prepare(operation).status()).isEqualTo(409);
        assertThatThrownBy(() -> client.prepare(operation)).isInstanceOf(ProblemException.class);
        assertThatThrownBy(() -> client.prepare(operation)).isInstanceOf(ProblemException.class);
        server.verify();
    }
}

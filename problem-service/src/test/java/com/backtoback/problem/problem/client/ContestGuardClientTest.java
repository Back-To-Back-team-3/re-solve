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

    /** 오류 본문이 없으면 확정 거절로 저장하지 않는다. */
    @Test
    @DisplayName("빈 원격 오류 응답은 확정 거절이 아니라 의존 장애로 처리한다")
    void emptyErrorResponse() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server
            .expect(requestTo("/internal/v1/contest/guards/PROBLEM/1/prepare"))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        var problem = Problem.create(1L, ProblemScope.GENERAL);
        ReflectionTestUtils.setField(problem, "id", 1L);
        var operation
            = ProblemStateOperation.hide("op", problem, "key", "hash", 1, ProblemVisibility.ARCHIVED, "reason");
        var client = new ContestGuardClient(builder.build(), JsonMapper.builder().build());
        assertThatThrownBy(() -> client.prepare(operation))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode())
                    .isEqualTo(com.backtoback.common.exception.CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
        server.verify();
    }
}

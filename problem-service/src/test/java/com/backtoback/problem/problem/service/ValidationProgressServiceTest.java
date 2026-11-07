package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.databind.json.JsonMapper;

/** Judge HTTP 응답과 로컬 반영 지연의 결합을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ValidationProgressServiceTest {
    @Mock
    private ValidationProgressStore store;
    private final CircuitBreaker circuit = CircuitBreaker.ofDefaults("progress");
    private ValidationProgressService service;
    private MockRestServiceServer server;

    /** 고정 회차와 원격 HTTP 테스트 서버를 준비한다. */
    @BeforeEach
    void prepare() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service
            = new ValidationProgressService(
                store,
                new JudgeProgressClient(builder.baseUrl("http://judge").build(), JsonMapper.builder().build(), circuit)
            );
        given(store.read(1, 2))
            .willReturn(new ValidationProgressStore.Snapshot("1", "4", "3", "java", false, List.of(), null));
    }

    /**
     * 테스트 순번과 실행 시간의 잘못된 값을 의존 장애로 처리한다.
     *
     * @param testJson 잘못된 테스트 결과
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"testOrder\":0}",
            "{\"testOrder\":-1}",
            "{\"testOrder\":1,\"timeMs\":-1}"
        }
    )
    @DisplayName("Judge의 잘못된 테스트 순번과 음수 실행 시간은 503으로 거절한다")
    void invalidTestValues(String testJson) {
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        "{\"validationId\":\"1\",\"problemRevisionId\":\"3\",\"language\":\"java\","
                            + "\"status\":\"RUNNING\",\"tests\":[" + testJson + "]}"
                    )
            );
        assertThatThrownBy(() -> service.read(1, 2))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }

    /** 미판정 테스트의 판정과 측정값은 null을 허용한다. */
    @Test
    @DisplayName("미판정 테스트는 판정과 실행 시간이 없어도 조회할 수 있다")
    void pendingTest() {
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                {"validationId":"1","problemRevisionId":"3","language":"java","status":"RUNNING",
                 "tests":[{"testOrder":1,"verdict":null,"timeMs":null,"memoryKb":null}]}
                """));
        assertThat(service.read(1, 2).tests()).hasSize(1);
    }

    /** Judge가 종결해도 이벤트 반영 전 상태를 유지한다. */
    @Test
    @DisplayName("Judge 종결 직후에는 테스트 판정과 결과 미반영을 함께 반환한다")
    void delayedResult() {
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                {"validationId":"1","problemRevisionId":"3","language":"java","status":"REJECTED",
                "tests":[{"testOrder":1,"verdict":"WA","timeMs":3,"memoryKb":4}],"maxTimeMs":3}
                """));
        var result = service.read(1, 2);
        assertThat(result.resultApplied()).isFalse();
        assertThat(result.tests().getFirst().verdict()).isEqualTo("WA");
        assertThat(result.maxTimeMs()).isEqualTo(3);
    }

    /** 테스트 원소가 없으면 공개 응답 생성 전에 Judge 장애로 기록한다. */
    @Test
    @DisplayName("Judge가 null 테스트 원소를 보내면 503을 반환하고 서킷 실패를 기록한다")
    void nullTestEntry() {
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                {"validationId":"1","problemRevisionId":"3","language":"java","status":"RUNNING","tests":[null]}
                """));
        assertThatThrownBy(() -> service.read(1, 2))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isOne();
    }

    /** 명시적인 작업 없음 응답을 접수 대기로 변환한다. */
    @Test
    @DisplayName("Judge의 작업 없음은 REQUESTED와 빈 테스트 목록으로 반환한다")
    void missingJob() {
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"code\":\"PROBLEM_VALIDATION_NOT_FOUND\"}}")
            );
        var result = service.read(1, 2);
        assertThat(result.status()).isEqualTo("REQUESTED");
        assertThat(result.tests()).isEmpty();
    }

    /**
     * 일반 404·서버 오류·다른 회차의 결과는 의존 장애로 처리한다.
     *
     * @param status 원격 상태 코드
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            404,
            500,
            200
        }
    )
    @DisplayName("작업 없음 이외의 실패와 연결 불일치는 503으로 구분한다")
    void dependencyFailure(int status) {
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body("""
                {"validationId":"1","problemRevisionId":"999","language":"java",
                 "status":"RUNNING","tests":[]}
                """));
        assertThatThrownBy(() -> service.read(1, 2))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }
}

package com.backtoback.judge.run.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 로컬 Gateway를 통해 일반·시험 Run과 실제 Judge0 연동을 확인한다. */
@Tag("run-live")
@EnabledIfSystemProperty(
    named = "judge.run.live",
    matches = "true"
)
@Timeout(45)
class RunLiveIntegrationTest {
    private static final String PRACTICE_PATH = "/api/v1/runs";
    private static final String SOURCE = "def solution(a):\n    return a\n";
    private static final String SLOW_SOURCE = "import time\n\ndef solution(a):\n    time.sleep(1.5)\n    return a\n";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static URI gateway;
    private static String memberToken;
    private static String outsiderToken;
    private static String examPath;
    private static String practiceProblem;
    private static String practiceRevision;
    private static String pinnedSampleOutput;

    /** 고정 회차와 현재 회차의 공개 예제 값이 다른 로컬 테스트 데이터를 사용한다. */
    @BeforeAll
    static void loadFixture() {
        gateway = URI.create(setting("RUN_LIVE_GATEWAY_URL"));
        assertThat(gateway.getScheme()).isIn("http", "https");
        assertThat(gateway.getHost()).isIn("localhost", "127.0.0.1", "[::1]");
        assertThat(gateway.getUserInfo()).isNull();
        memberToken = setting("RUN_LIVE_MEMBER_TOKEN");
        outsiderToken = setting("RUN_LIVE_OUTSIDER_TOKEN");
        assertThat(memberToken.equals(outsiderToken)).as("서로 다른 테스트 회원이 필요합니다.").isFalse();
        practiceProblem = id("RUN_LIVE_PRACTICE_PROBLEM_ID");
        practiceRevision = id("RUN_LIVE_PRACTICE_REVISION_ID");
        examPath = "/api/v1/exams/" + id("RUN_LIVE_EXAM_ID") + "/problems/" + id("RUN_LIVE_EXAM_PROBLEM_ID") + "/runs";
        pinnedSampleOutput = setting("RUN_LIVE_PINNED_SAMPLE_OUTPUT");
    }

    /** 일반 직접 입력과 시험 고정 예제를 실행한 뒤 일반 제출 이력이 유지되는지 확인한다. */
    @Test
    @DisplayName("일반 직접 입력과 시험 고정 예제를 실행해도 일반 제출 이력이 늘지 않는다")
    void runsPracticeAndPinnedExamWithoutPracticeHistory() throws Exception {
        var before = practiceHistory();
        var practice = HTTP.send(practiceRequest(SOURCE), BodyHandlers.ofString());
        var practiceItem = data(practice).path("results").path(0);
        assertThat(practiceItem.path("source").asText()).isEqualTo("CUSTOM");
        assertThat(practiceItem.path("actualReturnText").asText()).isEqualTo("7");

        var exam = HTTP.send(examRequest(memberToken, SOURCE), BodyHandlers.ofString());
        var examData = data(exam);
        var sample = examData.path("results").path(0);
        var custom = examData.path("results").path(1);
        assertThat(sample.path("type").asText()).isEqualTo("SAMPLE");
        assertThat(sample.path("output").asText()).isEqualTo(pinnedSampleOutput);
        assertThat(custom.path("type").asText()).isEqualTo("CUSTOM");
        assertThat(custom.path("output").asText()).isEqualTo("7");
        assertThat(examData.path("results").size()).isEqualTo(2);
        assertThat(practiceHistory()).isEqualTo(before);
    }

    /** 시험에 등록되지 않은 회원은 실행 결과를 받지 못한다. */
    @Test
    @DisplayName("미등록 회원의 시험 Run을 거절한다")
    void rejectsAnUnregisteredMember() throws Exception {
        var response = HTTP.send(examRequest(outsiderToken, SOURCE), BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("EXAM_NOT_REGISTERED");
    }

    /** 두 문맥을 동시에 호출하면 같은 회원의 실행 중 한 건만 허용한다. */
    @Test
    @DisplayName("같은 회원의 일반 Run과 시험 Run이 동시에 실행되지 않는다")
    void sharesTheRunLimitAcrossContexts() throws Exception {
        var practice = HTTP.sendAsync(practiceRequest(SLOW_SOURCE), BodyHandlers.ofString());
        var exam = HTTP.sendAsync(examRequest(memberToken, SLOW_SOURCE), BodyHandlers.ofString());
        var practiceResponse = practice.get();
        var examResponse = exam.get();
        assertThat(List.of(practiceResponse.statusCode(), examResponse.statusCode()))
            .containsExactlyInAnyOrder(200, 429);
        if (practiceResponse.statusCode() == 429) {
            assertThat(errorCode(practiceResponse)).isEqualTo("RUN_RATE_LIMITED");
        } else {
            assertThat(errorCode(examResponse)).isEqualTo("RUN_IN_PROGRESS");
        }
    }

    /** 회원의 일반 제출 첫 페이지와 전체 건수를 비교한다. */
    private JsonNode practiceHistory() throws Exception {
        var history = request("/api/v1/me/submissions?size=1", memberToken).GET().build();
        var page = data(HTTP.send(history, BodyHandlers.ofString()));
        assertThat(page.path("totalElements").isIntegralNumber()).isTrue();
        return page;
    }

    /** 일반 문제에 직접 입력 하나를 보낸다. */
    private HttpRequest practiceRequest(String source) {
        return post(
            PRACTICE_PATH,
            memberToken,
            Map
                .of(
                    "problemId",
                    practiceProblem,
                    "problemRevisionId",
                    practiceRevision,
                    "language",
                    "python3",
                    "sourceCode",
                    source,
                    "useSamples",
                    false,
                    "customInputs",
                    List.of(Map.of("argumentTexts", Map.of("a", "7")))
                )
        );
    }

    /** 시험에 고정된 예제와 직접 입력 하나를 요청한다. */
    private HttpRequest examRequest(String token, String source) {
        return post(
            examPath,
            token,
            Map.of("language", "python3", "sourceCode", source, "customTests", List.of(Map.of("args", List.of(7))))
        );
    }

    /** 사용자 토큰은 요청에만 넣고 응답 검증에는 사용하지 않는다. */
    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest
            .newBuilder(gateway.resolve(path))
            .timeout(Duration.ofSeconds(35))
            .header("Authorization", "Bearer " + token);
    }

    /** 공개 API에 JSON 요청을 보낸다. */
    private HttpRequest post(String path, String token, Map<String, Object> payload) {
        return request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
            .build();
    }

    /** 성공 응답의 데이터만 반환한다. */
    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("응답 코드: %s", errorCode(response)).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }

    /** 오류 본문 전체를 로그에 남기지 않고 코드만 읽는다. */
    private String errorCode(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("error").path("code").asText("");
    }

    /** 경로와 요청에 넣을 테스트 데이터 식별자를 읽는다. */
    private static String id(String key) {
        String value = setting(key);
        assertThat(value.matches("[1-9][0-9]{0,18}")).as("%s는 양수 ID여야 합니다.", key).isTrue();
        return value;
    }

    /** 누락한 설정은 이름만 출력하고 실제 값은 출력하지 않는다. */
    private static String setting(String key) {
        String value = System.getenv(key);
        assertThat(value == null || value.isBlank()).as("%s 설정이 필요합니다.", key).isFalse();
        return value;
    }
}

package com.backtoback.judge.judging.client;

import static com.backtoback.judge.judging.config.Judge0TestProperties.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.Judge0Configuration;
import com.backtoback.judge.judging.config.Judge0Properties;
import com.backtoback.judge.judging.config.Judge0Properties.Language;
import com.backtoback.judge.judging.config.Judge0Properties.Languages;
import com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
/** Judge0 실행 접수와 결과 변환 및 외부 오류 처리를 검증한다. */
@ExtendWith(MockitoExtension.class)
class Judge0ClientTest {
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<StubResponse> responses = new LinkedBlockingQueue<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private ExecutorService executor;
    private Judge0Client client;
    private String baseUrl;

    /**
     * 실제 HTTP를 받는 로컬 테스트 서버와 클라이언트를 준비한다.
     *
     * @throws IOException 테스트 서버를 열지 못한 경우
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = client(baseUrl, Duration.ofSeconds(2), "test-secret");
    }

    /**
     * 테스트 서버와 지연 응답 작업을 정리한다.
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * 생성 요청의 언어·자원 단위·Base64와 token 조회 흐름을 검증한다.
     */
    @Test
    @DisplayName("생성 요청의 언어·자원 단위·Base64와 token 조회 흐름을 검증한다")
    void createsThenPollsTheSameToken() {
        responses.add(new StubResponse(201, "{\"token\":\"token-123\"}", 0));
        responses.add(new StubResponse(200, result(1, null), 0));
        responses.add(new StubResponse(200, result(2, null), 0));
        responses.add(new StubResponse(200, result(3, "  정답\n"), 0));

        var created = client.create(request());
        String token = created.token();
        assertThat(created.runtime()).isEqualTo(new JudgeRuntime("java", 91, "Java (JDK 17.0.6)", "17.0.6", null));
        assertThat(token).isEqualTo("token-123");
        assertThat(client.find(token).state()).isEqualTo(State.QUEUED);
        assertThat(client.find(token).state()).isEqualTo(State.RUNNING);
        var completed = client.find(token);
        assertThat(completed.state()).isEqualTo(State.COMPLETED);
        assertThat(completed.statusId()).isEqualTo(3);
        assertThat(completed.stdout()).isEqualTo("  정답\n");
        assertThat(completed.timeSeconds()).isEqualByComparingTo("0.012");
        assertThat(completed.memoryKiB()).isEqualByComparingTo("1200.5");
        assertThat(completed.exitCode()).isZero();

        RecordedRequest create = requests.getFirst();
        assertThat(create.method()).isEqualTo("POST");
        assertThat(create.uri()).isEqualTo("/submissions?base64_encoded=true&wait=false");
        assertThat(create.authToken()).isEqualTo("test-secret");
        var body = mapper.readTree(create.body());
        assertThat(body.get("language_id").asInt()).isEqualTo(91);
        assertThat(new String(Base64.getDecoder().decode(body.get("source_code").asText()), StandardCharsets.UTF_8))
            .isEqualTo(request().sourceCode());
        assertThat(new String(Base64.getDecoder().decode(body.get("stdin").asText()), StandardCharsets.UTF_8))
            .isEqualTo(request().stdin());
        assertThat(body.get("cpu_time_limit").decimalValue()).isEqualByComparingTo("1.250");
        assertThat(body.get("wall_time_limit").decimalValue()).isEqualByComparingTo("6.250");
        assertThat(body.get("memory_limit").asInt()).isEqualTo(262144);
        assertThat(body.get("enable_network").asBoolean()).isFalse();
        assertThat(body.has("expected_output")).isFalse();
        assertThat(requests).hasSize(4);
        assertThat(requests.subList(1, 4)).allSatisfy(recorded -> {
            assertThat(recorded.method()).isEqualTo("GET");
            assertThat(recorded.uri()).startsWith("/submissions/token-123?base64_encoded=true&fields=");
        });
    }

    /**
     * 실행 종료와 엔진 자체 오류를 구분하고 Judge0 원래 상태를 유지하는지 검증한다.
     *
     * @param status Judge0 상태 ID
     * @param expected 상위 실행기에 전달할 상태
     */
    @ParameterizedTest
    @DisplayName("실행 종료와 엔진 자체 오류를 구분하고 Judge0 원래 상태를 유지하는지 검증한다")
    @CsvSource(
        {
            "4,COMPLETED",
            "5,COMPLETED",
            "6,COMPLETED",
            "7,COMPLETED",
            "8,COMPLETED",
            "9,COMPLETED",
            "10,COMPLETED",
            "11,COMPLETED",
            "12,COMPLETED",
            "13,SYSTEM_ERROR",
            "14,SYSTEM_ERROR"
        }
    )
    void distinguishesExecutionAndEngineFailures(int status, State expected) {
        responses.add(new StubResponse(200, result(status, null), 0));
        var response = client.find("token-123");
        assertThat(response.state()).isEqualTo(expected);
        assertThat(response.statusId()).isEqualTo(status);
        assertThat(response.stdout()).isNull();
    }

    /**
     * 컴파일·오류 출력·메시지의 Base64 줄바꿈을 복원하는지 검증한다.
     */
    @Test
    @DisplayName("컴파일·오류 출력·메시지의 Base64 줄바꿈을 복원하는지 검증한다")
    void decodesAllOutputFields() {
        responses.add(new StubResponse(200, """
            {"token":"token-123","status":{"id":6},"stdout":"",
             "stderr":"ZXJyb3I=\\n","compile_output":"ZXJyb3I=\\r\\n","message":"ZXJyb3I="}
            """, 0));
        var result = client.find("token-123");
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo("error");
        assertThat(result.compileOutput()).isEqualTo("error");
        assertThat(result.message()).isEqualTo("error");
    }

    /**
     * 외부 오류 응답의 상태만 보관하고 재요청이나 민감한 본문 기록을 하지 않는지 검증한다.
     *
     * @param status 외부 HTTP 오류 상태
     * @param output 캡처한 로그
     */
    @ParameterizedTest
    @DisplayName("외부 오류 응답의 상태만 보관하고 재요청이나 민감한 본문 기록을 하지 않는지 검증한다")
    @ValueSource(
        ints = {
            401,
            403,
            404,
            422,
            429,
            500,
            503
        }
    )
    void reportsHttpFailureWithoutRetryOrPayload(int status, CapturedOutput output) {
        responses.add(new StubResponse(status, "secret-source-code test-secret", 0));
        assertThatThrownBy(() -> client.create(request())).isInstanceOfSatisfying(JudgeException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED);
            assertThat(exception.getUpstreamStatus()).isEqualTo(status);
            assertThat(exception.getCause()).isNull();
        });
        assertThat(requests).hasSize(1);
        assertThat(output.getAll()).doesNotContain("secret-source-code", "test-secret");
    }

    /**
     * 생성 응답 유실과 조회 타임아웃 모두 재시도 없이 통신 실패로 전달하는지 검증한다.
     *
     * @param create 실행 생성 요청 여부
     */
    @ParameterizedTest
    @DisplayName("생성 응답 유실과 조회 타임아웃 모두 재시도 없이 통신 실패로 전달하는지 검증한다")
    @ValueSource(
        booleans = {
            true,
            false
        }
    )
    void reportsTimeoutWithoutRetry(boolean create) {
        client = client(baseUrl, Duration.ofMillis(100), "");
        responses.add(new StubResponse(200, result(3, null), 1000));
        assertThatThrownBy(() -> {
            if (create) {
                client.create(request());
            } else {
                client.find("token-123");
            }
        })
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_CONNECTION_FAILED)
            );
        assertThat(requests).hasSize(1);
    }

    /**
     * 연결 거부와 주소 미설정을 응답 오류와 구분하는지 검증한다.
     */
    @Test
    @DisplayName("연결 거부와 주소 미설정을 응답 오류와 구분하는지 검증한다")
    void reportsConnectionAndConfigurationFailures() {
        server.stop(0);
        assertThatThrownBy(() -> client.find("token-123"))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_CONNECTION_FAILED)
            );
        client = client("", Duration.ofSeconds(1), "");
        assertThatThrownBy(() -> client.find("token-123"))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_CONFIGURATION_INVALID)
            );
    }

    /**
     * 주소 미설정 시 실제 언어 조회보다 먼저 설정 오류로 실행 생성을 거부하는지 검증한다.
     *
     * @param missingBaseUrl 미설정 또는 공백인 Judge0 주소
     */
    @ParameterizedTest
    @DisplayName("주소 미설정이면 새 실행과 고정 런타임 실행 모두 설정 오류로 거부한다")
    @NullAndEmptySource
    @ValueSource(
        strings = {
            " ",
            "\t"
        }
    )
    void rejectsCreateWithoutConfiguredAddress(String missingBaseUrl) {
        var settings = properties(missingBaseUrl, true, false, false);
        var restClient = new Judge0Configuration(settings).judge0RestClient(RestClient.builder());
        var languageService = new JudgeLanguageService(settings, new Judge0LanguageClient(settings, restClient));
        var executionClient = new Judge0Client(restClient, settings, languageService);
        var fixedRuntime = new JudgeRuntime("java", 91, "Java (JDK 17.0.6)", "17.0.6", null);
        var newExecution = new JudgeExecutionCreateRequest("java", null, "code", "", 1000, 6000, 1024);
        var fixedExecution = new JudgeExecutionCreateRequest("java", fixedRuntime, "code", "", 1000, 6000, 1024);

        for (var execution : List.of(newExecution, fixedExecution)) {
            assertThatThrownBy(() -> executionClient.create(execution))
                .isInstanceOfSatisfying(JudgeException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_CONFIGURATION_INVALID);
                    assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(503);
                });
        }
        assertThat(languageService.findAll()).allSatisfy(language -> assertThat(language.enabled()).isFalse());
        assertThat(requests).isEmpty();
    }

    /**
     * 빈 응답·잘못된 JSON·누락되거나 잘못된 token을 거부하는지 검증한다.
     *
     * @param body 잘못된 생성 응답
     */
    @ParameterizedTest
    @DisplayName("빈 응답·잘못된 JSON·누락되거나 잘못된 token을 거부하는지 검증한다")
    @ValueSource(
        strings = {
            "",
            "{",
            "{}",
            "null",
            "{\"token\":\"\"}",
            "{\"token\":\"../bad\"}"
        }
    )
    void rejectsMalformedCreateResponse(String body) {
        responses.add(new StubResponse(201, body, 0));
        assertThatThrownBy(() -> client.create(request()))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED)
            );
        assertThat(requests).hasSize(1);
    }

    /**
     * 결과 응답의 token·상태·출력 형식이 잘못되면 재생성 없이 오류로 전달하는지 검증한다.
     *
     * @param body 잘못된 조회 응답
     */
    @ParameterizedTest
    @DisplayName("결과 응답의 token·상태·출력 형식이 잘못되면 재생성 없이 오류로 전달하는지 검증한다")
    @ValueSource(
        strings = {
            "{}",
            "{\"token\":\"other\",\"status\":{\"id\":3}}",
            "{\"token\":\"token-123\",\"status\":{}}",
            "{\"token\":\"token-123\",\"status\":{\"id\":99}}",
            "{\"token\":\"token-123\",\"status\":{\"id\":3},\"stdout\":\"not@base64\"}"
        }
    )
    void rejectsMalformedResultWithoutRecreating(String body) {
        responses.add(new StubResponse(200, body, 0));
        assertThatThrownBy(() -> client.find("token-123"))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED)
            );
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().method()).isEqualTo("GET");
    }

    /**
     * 비활성·미지원 언어는 외부 호출 전에 거부하고 인증 정보가 없으면 헤더를 생략하는지 검증한다.
     */
    @Test
    @DisplayName("비활성·미지원 언어는 외부 호출 전에 거부하고 인증 정보가 없으면 헤더를 생략하는지 검증한다")
    void rejectsUnsupportedLanguagesAndOmitsEmptyCredentials() {
        for (String language : List.of("cpp", "python3", "javascript", "JAVA")) {
            var request = new JudgeExecutionCreateRequest(language, null, "code", "", 1000, 6000, 1024);
            assertThatThrownBy(() -> client.create(request))
                .isInstanceOfSatisfying(
                    JudgeException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.UNSUPPORTED_LANGUAGE)
                );
        }
        assertThat(requests).isEmpty();
        client = client(baseUrl, Duration.ofSeconds(1), "");
        responses.add(new StubResponse(201, "{\"token\":\"token-123\"}", 0));
        client.create(request());
        assertThat(requests.getFirst().authToken()).isNull();
    }

    /**
     * 리다이렉트 응답을 따라가거나 외부에 인증 정보를 전달하지 않는지 검증한다.
     */
    @Test
    @DisplayName("리다이렉트 응답을 따라가거나 외부에 인증 정보를 전달하지 않는지 검증한다")
    void rejectsRedirects() {
        responses.add(new StubResponse(302, "{}", 0));
        assertThatThrownBy(() -> client.create(request()))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED)
            );
        assertThat(requests).hasSize(1);
    }

    /**
     * Judge0에서 선택한 C++ ID와 C++17 옵션이 실제 제출 요청에 전달되는지 검증한다.
     */
    @Test
    @DisplayName("Judge0에서 선택한 C++ ID와 C++17 옵션이 실제 제출 요청에 전달되는지 검증한다")
    void submitsWithDiscoveredCppRuntime() {
        var configuration
            = new Judge0Configuration(
                new Judge0Properties(
                    baseUrl,
                    "X-Auth-Token",
                    "test-secret",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    new Languages(new Language(true), new Language(true), new Language(true))
                )
            );
        var restClient = configuration.judge0RestClient(RestClient.builder());
        var languageService
            = new JudgeLanguageService(
                properties(baseUrl, false, false, true),
                new Judge0LanguageClient(properties(baseUrl), restClient)
            );
        client = new Judge0Client(restClient, properties(baseUrl), languageService);
        responses.add(new StubResponse(200, "[{\"id\":123,\"name\":\"C++ (GCC 12.2.0)\"}]", 0));
        responses.add(new StubResponse(201, "{\"token\":\"token-123\"}", 0));
        var created = client.create(new JudgeExecutionCreateRequest("cpp", null, "code", "", 1000, 6000, 1024));
        assertThat(created.token()).isEqualTo("token-123");
        assertThat(created.runtime())
            .isEqualTo(new JudgeRuntime("cpp", 123, "C++ (GCC 12.2.0)", "12.2.0", "-std=c++17"));
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().uri()).isEqualTo("/languages/");
        assertThat(requests.getFirst().authToken()).isEqualTo("test-secret");
        var body = mapper.readTree(requests.get(1).body());
        assertThat(body.get("language_id").asInt()).isEqualTo(123);
        assertThat(body.get("compiler_options").asText()).isEqualTo("-std=c++17");
    }

    /**
     * 일반 실행과 재채점이 언어 목록을 한 번만 조회하고 각각 선택한 ID로 실행하는지 검증한다.
     */
    @Test
    @DisplayName("일반 실행과 재채점이 언어 목록을 한 번만 조회하고 각각 선택한 ID로 실행하는지 검증한다")
    void sharesCatalogForLatestAndFixedExecutionRequests() {
        var configuration
            = new Judge0Configuration(
                new Judge0Properties(
                    baseUrl,
                    "X-Auth-Token",
                    "",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    new Languages(new Language(true), new Language(true), new Language(true))
                )
            );
        var restClient = configuration.judge0RestClient(RestClient.builder());
        var languageService
            = new JudgeLanguageService(
                properties(baseUrl, true, false, false),
                new Judge0LanguageClient(properties(baseUrl), restClient)
            );
        client = new Judge0Client(restClient, properties(baseUrl), languageService);
        responses.add(new StubResponse(200, """
            [{"id":100,"name":"Java (JDK 17.0.6)"},
             {"id":101,"name":"Java (JDK 17.0.10)"},
             {"id":99,"name":"Java (JDK 17.0.6)"}]
            """, 0));
        for (int index = 1; index <= 3; index++) {
            responses.add(new StubResponse(201, "{\"token\":\"token-%d\"}".formatted(index), 0));
        }
        var original = client.create(new JudgeExecutionCreateRequest("java", null, "code", "", 1000, 6000, 1024));
        // 과거에 기록된 환경은 최신 버전이나 같은 버전의 작은 ID로 바꾸지 않는다.
        var fixed = new JudgeRuntime("java", 100, "Java (JDK 17.0.6)", "17.0.6", null);
        var rejudged = client.create(new JudgeExecutionCreateRequest("java", fixed, "code", "", 1000, 6000, 1024));
        var latest = client.create(new JudgeExecutionCreateRequest("java", null, "code", "", 1000, 6000, 1024));

        assertThat(original.token()).isEqualTo("token-1");
        assertThat(original.runtime()).isEqualTo(new JudgeRuntime("java", 101, "Java (JDK 17.0.10)", "17.0.10", null));
        assertThat(rejudged.token()).isEqualTo("token-2");
        assertThat(rejudged.runtime()).isEqualTo(fixed);
        assertThat(latest.token()).isEqualTo("token-3");
        assertThat(latest.runtime()).isEqualTo(original.runtime());
        assertThat(requests).hasSize(4);
        assertThat(requests.getFirst().uri()).isEqualTo("/languages/");
        assertThat(mapper.readTree(requests.get(1).body()).get("language_id").asInt()).isEqualTo(101);
        assertThat(mapper.readTree(requests.get(2).body()).get("language_id").asInt()).isEqualTo(100);
        assertThat(mapper.readTree(requests.get(3).body()).get("language_id").asInt()).isEqualTo(101);
    }

    /**
     * 기존 ID의 정보가 달라지면 다른 ID로 실행을 생성하지 않는지 검증한다.
     *
     * @param catalog 기록과 불일치하는 현재 실행 환경 목록
     */
    @ParameterizedTest
    @DisplayName("기존 ID의 정보가 달라지면 다른 ID로 실행을 생성하지 않는지 검증한다")
    @ValueSource(
        strings = {
            "[{\"id\":999,\"name\":\"Java (JDK 17.0.6)\"}]",
            "[{\"id\":100,\"name\":\"Java (OpenJDK 17.0.6)\"}]",
            "[{\"id\":100,\"name\":\"Java (JDK 17.0.10)\"}]"
        }
    )
    void rejectsRejudgeWithoutCreatingExecution(String catalog) {
        var configuration
            = new Judge0Configuration(
                new Judge0Properties(
                    baseUrl,
                    "X-Auth-Token",
                    "",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    new Languages(new Language(true), new Language(true), new Language(true))
                )
            );
        var restClient = configuration.judge0RestClient(RestClient.builder());
        var languageService
            = new JudgeLanguageService(
                properties(baseUrl, true, false, false),
                new Judge0LanguageClient(properties(baseUrl), restClient)
            );
        client = new Judge0Client(restClient, properties(baseUrl), languageService);
        responses.add(new StubResponse(200, catalog, 0));
        var previous = new JudgeRuntime("java", 100, "Java (JDK 17.0.6)", "17.0.6", null);
        assertThatThrownBy(
            () -> client.create(new JudgeExecutionCreateRequest("java", previous, "code", "", 1000, 6000, 1024))
        )
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE)
            );
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().method()).isEqualTo("GET");
    }

    /**
     * 실제 전송 설정을 사용하는 테스트 클라이언트를 만든다.
     *
     * @param url 로컬 서버 주소
     * @param readTimeout 응답 제한 시간
     * @param token 인증 헤더 값
     * @return 테스트 클라이언트
     */
    private Judge0Client client(String url, Duration readTimeout, String token) {
        var configuration
            = new Judge0Configuration(
                new Judge0Properties(
                    url,
                    "X-Auth-Token",
                    token,
                    Duration.ofSeconds(1),
                    readTimeout,
                    new Languages(new Language(true), new Language(true), new Language(true))
                )
            );
        var restClient = configuration.judge0RestClient(RestClient.builder());
        // HTTP 생성·조회 테스트에서 공유하는 언어 조회 대역이다. 개별 호출에 대한 스텁은 등록하지 않는다.
        var languages = mock(JudgeLanguageService.class, invocation -> {
            if (!invocation.getMethod().getName().equals("requireRuntime")) {
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            if ("java".equals(invocation.getArgument(0))) {
                return new JudgeRuntime("java", 91, "Java (JDK 17.0.6)", "17.0.6", null);
            }
            throw new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
        });
        return new Judge0Client(restClient, properties(url), languages);
    }

    /**
     * UTF-8 코드·입력과 소수 초로 변환할 제한값을 준비한다.
     *
     * @return 실행 요청
     */
    private JudgeExecutionCreateRequest request() {
        return new JudgeExecutionCreateRequest("java", null, "class Main { /* 한글 */ }", "1\n가나다", 1250, 6250, 262144);
    }

    /**
     * 정상 Judge0 응답 구조를 생성한다.
     *
     * @param status 실행 상태 ID
     * @param stdout 표준 출력; null 허용
     * @return JSON 응답
     */
    private String result(int status, String stdout) {
        String output
            = stdout == null ? "null"
                : "\"" + Base64.getEncoder().encodeToString(stdout.getBytes(StandardCharsets.UTF_8)) + "\"";
        return "{\"token\":\"token-123\",\"status\":{\"id\":" + status + "},\"stdout\":" + output
            + ",\"time\":\"0.012\",\"memory\":1200.5,\"exit_code\":0}";
    }

    /**
     * 요청을 기록하고 미리 준비한 HTTP 응답을 반환한다.
     *
     * @param exchange 테스트 HTTP 요청
     * @throws IOException 요청 본문 또는 응답을 전송하지 못한 경우
     */
    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests
                .add(
                    new RecordedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("X-Auth-Token"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    )
                );
            StubResponse response = responses.remove();
            if (response.delayMs() > 0) {
                try {
                    Thread.sleep(response.delayMs());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (response.status() == 302) {
                exchange.getResponseHeaders().set("Location", baseUrl + "/redirect-target");
            }
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private record StubResponse(int status, String body, long delayMs) {
    }

    private record RecordedRequest(String method, String uri, String authToken, String body) {
    }
}

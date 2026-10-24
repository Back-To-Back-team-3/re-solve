package com.backtoback.judge.judging.service;

import static com.backtoback.judge.judging.config.Judge0TestProperties.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.Judge0LanguageClient;
import com.backtoback.judge.judging.dto.response.Judge0LanguageResponse;

/**
 * 실행 환경 선택·고정 환경 검증과 캐시의 실패·동시성 계약을 검증한다.
 */
@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class JudgeLanguageServiceTest {
    private static final String CATALOG = """
        [{"id":900,"name":"Java (JDK 17.0.6)"},
         {"id":901,"name":"Python (3.11.2)"},
         {"id":902,"name":"C++ (GCC 12.2.0)"},
         {"id":62,"name":"Java (OpenJDK 13.0.1)"},
         {"id":70,"name":"Python (2.7.17)"}]
        """;
    private MockRestServiceServer server;
    private RestClient client;
    private JudgeLanguageService service;

    /**
     * 언어 ID 설정 없이 조회 요청을 검증할 HTTP 모의 서버를 준비한다.
     */
    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://judge0.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        service = service("http://judge0.test", true);
    }

    /**
     * 조회한 ID·버전을 사용하고 API 조회와 제출에서 같은 목록을 재사용하는지 검증한다.
     */
    @Test
    @DisplayName("조회한 ID·버전을 사용하고 API 조회와 제출에서 같은 목록을 재사용하는지 검증한다")
    void discoversRuntimeAndCachesSuccessfulResponse() {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime("java", null).judge0LanguageId()).isEqualTo(900);
        assertThat(service.requireRuntime("python3", null).judge0LanguageId()).isEqualTo(901);
        assertThat(service.requireRuntime("cpp", null).judge0LanguageId()).isEqualTo(902);
        assertThat(service.findAll())
            .extracting(language -> language.runtime().runtimeVersion())
            .containsExactly("17.0.6", "3.11.2", "12.2.0");
        assertThat(service.findAll())
            .extracting(
                language -> language.language().getCode(),
                language -> language.language().getDisplayName(),
                language -> language.enabled()
            )
            .containsExactly(
                tuple("java", "Java 17", true),
                tuple("python3", "Python 3", true),
                tuple("cpp", "C++17", true)
            );
        server.verify();
    }

    /**
     * 고정 환경이 있으면 이전 ID를 검증하고 없으면 최신 환경을 선택하는지 검증한다.
     *
     * @param code 고정할 언어
     * @param version 기록된 숫자 버전
     * @param id 기록된 Judge0 ID
     * @param name 기록된 실행 환경 이름
     */
    @ParameterizedTest
    @DisplayName("고정 환경이 있으면 이전 ID를 검증하고 없으면 최신 환경을 선택하는지 검증한다")
    @CsvSource(
        {
            "java,17.0.6,900,Java (JDK 17.0.6)",
            "python3,3.11.2,901,Python (3.11.2)",
            "cpp,12.2.0,902,C++ (GCC 12.2.0)"
        }
    )
    void selectsLatestOrValidatesFixedRuntime(String code, String version, int id, String name) {
        var fixed = new JudgeRuntime(code, id, name, version, code.equals("cpp") ? "-std=c++17" : null);
        String catalog = CATALOG.strip().replace("]", "") + """
            ,{"id":910,"name":"Java (JDK 17.0.10)"},
             {"id":911,"name":"Python (3.14.0)"},
             {"id":912,"name":"C++ (GCC 14.1.0)"}]
            """;
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime(code, fixed)).isEqualTo(fixed);
        // 고정 환경으로 실행한 뒤에도 다른 요청에서 null을 넘기면 최신 환경을 선택한다.
        assertThat(service.requireRuntime("java", null).judge0LanguageId()).isEqualTo(910);
        assertThat(service.requireRuntime("python3", null).judge0LanguageId()).isEqualTo(911);
        assertThat(service.requireRuntime("cpp", null).judge0LanguageId()).isEqualTo(912);
        assertThat(service.findAll())
            .extracting(language -> language.runtime().runtimeVersion())
            .containsExactly("17.0.10", "3.14.0", "14.1.0");
        server.verify();
    }

    /**
     * 같은 버전의 더 작은 ID가 있어도 기록된 ID를 검증하고 컴파일 옵션을 유지하는지 검증한다.
     *
     * @param code 기록된 언어
     * @param id 기록된 ID
     * @param name 기록된 실행 환경 이름
     * @param version 기록된 숫자 버전
     */
    @ParameterizedTest
    @DisplayName("같은 버전의 더 작은 ID가 있어도 기록된 ID를 검증하고 컴파일 옵션을 유지하는지 검증한다")
    @CsvSource(
        {
            "java,900,Java (JDK 17.0.6),17.0.6",
            "python3,901,Python (3.11.2),3.11.2",
            "cpp,902,C++ (GCC 12.2.0),12.2.0"
        }
    )
    void reusesExactRuntimeId(String code, int id, String name, String version) {
        var previous = new JudgeRuntime(code, id, name, version, code.equals("cpp") ? "-std=c++17" : null);
        String catalog = "[{\"id\":%d,\"name\":\"%s\"},{\"id\":1,\"name\":\"%s\"}]".formatted(id, name, name);
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime(code, previous)).isEqualTo(previous);
        server.verify();
    }

    /**
     * 고정 환경의 ID·이름·버전이 캐시된 목록과 다르면 재조회 없이 거부하는지 검증한다.
     *
     * @param catalog 기록된 환경과 일치하지 않는 최초 조회 목록
     */
    @ParameterizedTest
    @DisplayName("고정 환경의 ID·이름·버전이 캐시된 목록과 다르면 재조회 없이 거부하는지 검증한다")
    @ValueSource(
        strings = {
            "[]",
            "[{\"id\":999,\"name\":\"Java (JDK 17.0.6)\"}]",
            "[{\"id\":900,\"name\":\"Java (OpenJDK 17.0.6)\"}]",
            "[{\"id\":900,\"name\":\"Java (JDK 17.0.10)\"}]"
        }
    )
    void rejectsRuntimeThatDoesNotMatchCachedCatalog(String catalog) {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        service.findAll();
        var previous = new JudgeRuntime("java", 900, "Java (JDK 17.0.6)", "17.0.6", null);
        assertThatThrownBy(() -> service.requireRuntime("java", previous))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE)
            );
        server.verify();
    }

    /**
     * ID와 이름이 같아도 기록된 숫자 버전이 다르면 재채점을 거부하는지 검증한다.
     *
     * @param version 이름에서 추출한 실제 버전과 다른 기록
     */
    @ParameterizedTest
    @DisplayName("ID와 이름이 같아도 기록된 숫자 버전이 다르면 재채점을 거부하는지 검증한다")
    @NullAndEmptySource
    @ValueSource(
        strings = {
            "17.0.10",
            "17.0.6.0"
        }
    )
    void rejectsMismatchedStoredVersion(String version) {
        var previous = new JudgeRuntime("java", 900, "Java (JDK 17.0.6)", version, null);
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.requireRuntime("java", previous))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE)
            );
        server.verify();
    }

    /**
     * 최초 조회한 목록과 언어별 최신 실행 환경을 일반 조회와 반복 재채점에서 재사용하는지 검증한다.
     */
    @Test
    @DisplayName("최초 조회한 목록과 언어별 최신 실행 환경을 일반 조회와 반복 재채점에서 재사용하는지 검증한다")
    void sharesFirstSuccessfulCatalogAndLatestRuntimesAcrossRequests() {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        // 목록 조회로 먼저 초기화한 뒤 세 언어 모두 같은 실행 환경 객체를 계속 반환해야 한다.
        service.findAll();
        var runtimes
            = List
                .of(
                    service.requireRuntime("java", null),
                    service.requireRuntime("python3", null),
                    service.requireRuntime("cpp", null)
                );
        for (int index = 0; index < 3; index++) {
            for (JudgeRuntime runtime : runtimes) {
                assertThat(service.requireRuntime(runtime.language(), runtime)).isSameAs(runtime);
                assertThat(service.requireRuntime(runtime.language(), null)).isSameAs(runtime);
            }
            assertThat(service.findAll()).hasSize(3);
        }
        assertThat(runtimes.get(2).compilerOptions()).isEqualTo("-std=c++17");
        server.verify();
    }

    /**
     * 언어 불일치·잘못된 ID·비활성 언어를 외부 호출 전에 거부하는지 검증한다.
     */
    @Test
    @DisplayName("언어 불일치·잘못된 ID·비활성 언어를 외부 호출 전에 거부하는지 검증한다")
    void rejectsInvalidStoredRuntimeBeforeLookup() {
        var previous = new JudgeRuntime("java", 900, "Java (JDK 17.0.6)", "17.0.6", null);
        assertThatThrownBy(() -> service.requireRuntime("python3", previous))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE)
            );
        var invalidId = new JudgeRuntime("java", 0, "Java (JDK 17.0.6)", "17.0.6", null);
        assertThatThrownBy(() -> service.requireRuntime("java", invalidId))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE)
            );
        service = service("http://judge0.test", false);
        assertThatThrownBy(() -> service.requireRuntime("java", previous))
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE)
            );
        server.verify();
    }

    /**
     * Judge0에 실행 환경이 있어도 서비스의 활성화 설정이 없으면 실행을 거부하는지 검증한다.
     */
    @Test
    @DisplayName("Judge0에 실행 환경이 있어도 서비스의 활성화 설정이 없으면 실행을 거부하는지 검증한다")
    void preservesExplicitActivationPolicy() {
        service = service("http://judge0.test", false);
        assertThatThrownBy(() -> service.requireRuntime("java", null).judge0LanguageId())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.UNSUPPORTED_LANGUAGE)
            );
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        assertThat(service.findAll()).allSatisfy(language -> assertThat(language.enabled()).isFalse());
        assertThat(service.findAll().getFirst().runtime().runtimeVersion()).isEqualTo("17.0.6");
        server.verify();
    }

    /**
     * 지원 후보가 없으면 실행하지 않는지 검증한다.
     *
     * @param catalog 지원 후보가 없는 목록
     */
    @ParameterizedTest
    @DisplayName("지원 후보가 없으면 실행하지 않는지 검증한다")
    @ValueSource(
        strings = {
            "[]",
            "[{\"id\":62,\"name\":\"Java (OpenJDK 13.0.1)\"}]"
        }
    )
    void rejectsMissingRuntime(String catalog) {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.requireRuntime("java", null).judge0LanguageId())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.UNSUPPORTED_LANGUAGE)
            );
        assertThat(service.findAll().getFirst().enabled()).isFalse();
        server.verify();
    }

    /**
     * ID·목록 순서와 무관하게 지원 범위에서 가장 높은 숫자 버전을 선택하는지 검증한다.
     *
     * @param reversed 응답 목록을 역순으로 배치할지 여부
     */
    @ParameterizedTest
    @DisplayName("ID·목록 순서와 무관하게 지원 범위에서 가장 높은 숫자 버전을 선택하는지 검증한다")
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void selectsHighestStableVersionRegardlessOfOrder(boolean reversed) {
        var entries
            = new ArrayList<>(
                List
                    .of(
                        "{\"id\":990,\"name\":\"Java (OpenJDK 17.0.9)\"}",
                        "{\"id\":21,\"name\":\"Java (JDK 17.0.10)\"}",
                        "{\"id\":991,\"name\":\"Java (JDK 21.0.1)\"}",
                        "{\"id\":992,\"name\":\"Python (3.9.20)\"}",
                        "{\"id\":22,\"name\":\"Python (3.14.0)\"}",
                        "{\"id\":993,\"name\":\"Python (3.14.0rc1)\"}",
                        "{\"id\":994,\"name\":\"Python (3.15.0-beta)\"}",
                        "{\"id\":995,\"name\":\"Python (4.0.0)\"}",
                        "{\"id\":996,\"name\":\"C++ (GCC 9.5.0)\"}",
                        "{\"id\":23,\"name\":\"C++ (GCC 14.1.0)\"}",
                        "{\"id\":997,\"name\":\"C++ (Clang 19.1.7)\"}",
                        "{\"id\":998,\"name\":\"Java (JDK 17.0.11-ea)\"}",
                        "{\"id\":999,\"name\":\"C++ (GCC 15.0.0-preview)\"}"
                    )
            );
        if (reversed) {
            Collections.reverse(entries);
        }
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess("[" + String.join(",", entries) + "]", MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime("java", null).judge0LanguageId()).isEqualTo(21);
        assertThat(service.requireRuntime("python3", null).judge0LanguageId()).isEqualTo(22);
        assertThat(service.requireRuntime("cpp", null).judge0LanguageId()).isEqualTo(23);
        assertThat(service.findAll())
            .extracting(language -> language.runtime().runtimeVersion())
            .containsExactly("17.0.10", "3.14.0", "14.1.0");
        server.verify();
    }

    /**
     * 목록 순서와 무관하게 최신 버전 중 가장 작은 ID를 선택하고 낮은 버전의 작은 ID는 무시하는지 검증한다.
     *
     * @param reversed 목록을 역순으로 배치할지 여부
     */
    @ParameterizedTest
    @DisplayName("목록 순서와 무관하게 최신 버전 중 가장 작은 ID를 선택하고 낮은 버전의 작은 ID는 무시하는지 검증한다")
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void selectsLowestIdAmongHighestVersionRegardlessOfOrder(boolean reversed) {
        var entries
            = new ArrayList<>(
                List
                    .of(
                        "{\"id\":20,\"name\":\"Java (JDK 17.0.10)\"}",
                        "{\"id\":10,\"name\":\"Java (OpenJDK 17.0.10)\"}",
                        "{\"id\":1,\"name\":\"Java (JDK 17.0.9)\"}"
                    )
            );
        if (reversed) {
            Collections.reverse(entries);
        }
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess("[" + String.join(",", entries) + "]", MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime("java", null).judge0LanguageId()).isEqualTo(10);
        assertThat(service.findAll().getFirst().runtime().runtimeVersion()).isEqualTo("17.0.10");
        assertThat(service.findAll().getFirst().enabled()).isTrue();
        server.verify();
    }

    /**
     * 낮은 버전의 중복은 무시하고 더 높은 유일한 버전을 선택하는지 검증한다.
     *
     * @param highestFirst 최고 버전을 목록 앞에 배치할지 여부
     */
    @ParameterizedTest
    @DisplayName("낮은 버전의 중복은 무시하고 더 높은 유일한 버전을 선택하는지 검증한다")
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void ignoresDuplicatesBelowHighestVersion(boolean highestFirst) {
        String lower = "{\"id\":1,\"name\":\"Java (JDK 17.0.1)\"}," + "{\"id\":2,\"name\":\"Java (OpenJDK 17.0.1)\"}";
        String highest = "{\"id\":3,\"name\":\"Java (OpenJDK 17.0.2)\"}";
        String catalog = "[" + (highestFirst ? highest + "," + lower : lower + "," + highest) + "]";
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime("java", null).judge0LanguageId()).isEqualTo(3);
        server.verify();
    }

    /**
     * 끝의 0 생략 여부가 다른 같은 버전도 가장 작은 ID를 선택하는지 검증한다.
     *
     * @param code 서비스 언어 코드
     * @param first 첫 번째 실행 환경 이름
     * @param second 같은 버전의 두 번째 실행 환경 이름
     */
    @ParameterizedTest
    @DisplayName("끝의 0 생략 여부가 다른 같은 버전도 가장 작은 ID를 선택하는지 검증한다")
    @CsvSource(
        {
            "java,Java (JDK 17),Java (OpenJDK 17.0.0)",
            "python3,Python (3.14),Python (3.14.0)",
            "cpp,C++ (GCC 14.1),C++ (GCC 14.1.0)"
        }
    )
    void selectsLowestIdForEquivalentHighestVersions(String code, String first, String second) {
        String catalog = "[{\"id\":1,\"name\":\"%s\"},{\"id\":2,\"name\":\"%s\"}]".formatted(first, second);
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        assertThat(service.requireRuntime(code, null).judge0LanguageId()).isEqualTo(1);
        assertThat(service.findAll())
            .filteredOn(language -> language.language().getCode().equals(code))
            .singleElement()
            .satisfies(language -> assertThat(language.enabled()).isTrue());
        server.verify();
    }

    /**
     * 시험판 또는 해석할 수 없는 이름만 있으면 실행을 허용하지 않는지 검증한다.
     *
     * @param code 서비스 언어 코드
     * @param name 지원하지 않는 실행 환경 이름
     */
    @ParameterizedTest
    @DisplayName("시험판 또는 해석할 수 없는 이름만 있으면 실행을 허용하지 않는지 검증한다")
    @CsvSource(
        {
            "java,Java (JDK 17.0.11-ea)",
            "java,JavaFX (JDK 17.0.6)",
            "python3,Python (3.15.0rc1)",
            "python3,Python (latest)",
            "cpp,C++ (GCC 15.0.0-beta)",
            "cpp,C++ (Clang 19.1.7)"
        }
    )
    void rejectsPreviewAndUnknownRuntimeNames(String code, String name) {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess("[{\"id\":1,\"name\":\"%s\"}]".formatted(name), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.requireRuntime(code, null).judge0LanguageId())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.UNSUPPORTED_LANGUAGE)
            );
        server.verify();
    }

    /**
     * 실패한 조회는 저장하지 않아 다음 호출에서 정상 목록을 다시 조회하는지 검증한다.
     */
    @Test
    @DisplayName("실패한 조회는 저장하지 않아 다음 호출에서 정상 목록을 다시 조회하는지 검증한다")
    void retriesOnNextRequestAfterHttpFailure() {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.findAll()).isInstanceOfSatisfying(JudgeException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED);
            assertThat(exception.getUpstreamStatus()).isEqualTo(503);
        });
        var runtime = service.requireRuntime("java", null);
        assertThat(runtime.judge0LanguageId()).isEqualTo(900);
        assertThat(service.requireRuntime("java", null)).isSameAs(runtime);
        server.verify();
    }

    /**
     * 연결 오류는 실행 언어 미지원 오류와 구분하는지 검증한다.
     */
    @Test
    @DisplayName("연결 오류는 실행 언어 미지원 오류와 구분하는지 검증한다")
    void reportsConnectionFailure() {
        server.expect(requestTo("http://judge0.test/languages/")).andRespond(request -> {
            throw new IOException("connection failed");
        });
        assertThatThrownBy(() -> service.findAll())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_CONNECTION_FAILED)
            );
        server.verify();
    }

    /**
     * 빈 본문·잘못된 JSON·불완전하거나 중복된 ID는 캐시에 보관하지 않는지 검증한다.
     *
     * @param catalog 잘못된 목록 응답
     */
    @ParameterizedTest
    @DisplayName("빈 본문·잘못된 JSON·불완전하거나 중복된 ID는 캐시에 보관하지 않는지 검증한다")
    @ValueSource(
        strings = {
            "",
            "null",
            "{",
            "{}",
            "[null]",
            "[{}]",
            "[{\"id\":0,\"name\":\"Java (OpenJDK 17)\"}]",
            "[{\"id\":1,\"name\":\"\"}]",
            "[{\"id\":1,\"name\":\"Java (OpenJDK 17)\"},{\"id\":1,\"name\":\"Python (3.11)\"}]"
        }
    )
    void rejectsMalformedResponseWithoutCaching(String catalog) {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.findAll())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED)
            );
        var runtime = service.requireRuntime("java", null);
        assertThat(runtime.judge0LanguageId()).isEqualTo(900);
        assertThat(service.requireRuntime("java", null)).isSameAs(runtime);
        server.verify();
    }

    /**
     * 동시에 처음 조회해도 한 번 받은 목록을 공유하는지 검증한다.
     *
     * @throws Exception 비동기 실행 또는 결과 조회에 실패한 경우
     */
    @Test
    @DisplayName("동시에 처음 조회해도 한 번 받은 목록을 공유하는지 검증한다")
    void sharesCatalogAcrossConcurrentRequests() throws Exception {
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.requireRuntime("java", null));
            var fixed = new JudgeRuntime("python3", 901, "Python (3.11.2)", "3.11.2", null);
            var second = executor.submit(() -> service.requireRuntime("python3", fixed).judge0LanguageId());
            assertThat(first.get().judge0LanguageId()).isEqualTo(900);
            assertThat(service.requireRuntime("java", null)).isSameAs(first.get());
            assertThat(service.requireRuntime("cpp", null).judge0LanguageId()).isEqualTo(902);
            assertThat(second.get()).isEqualTo(901);
        }
        server.verify();
    }

    /**
     * Enum으로 변환할 수 없는 API 코드는 외부 요청 없이 거부하는지 검증한다.
     *
     * @param code 지원하지 않는 언어 코드
     */
    @ParameterizedTest
    @DisplayName("Enum으로 변환할 수 없는 API 코드는 외부 요청 없이 거부하는지 검증한다")
    @NullAndEmptySource
    @ValueSource(
        strings = {
            "javascript",
            "JAVA",
            "PYTHON3",
            "CPP",
            "Java 17",
            " java",
            "cpp "
        }
    )
    void rejectsUnsupportedLanguageCodes(String code) {
        assertThatThrownBy(() -> service.requireRuntime(code, null).judge0LanguageId())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.UNSUPPORTED_LANGUAGE)
            );
        server.verify();
    }

    /**
     * 서버 주소 미설정 시 외부 요청 없이 모든 언어를 비활성으로 응답하는지 검증한다.
     */
    @Test
    @DisplayName("서버 주소 미설정 시 외부 요청 없이 모든 언어를 비활성으로 응답하는지 검증한다")
    void avoidsRequestForMissingServer() {
        service = service("", true);
        assertThat(service.findAll()).allSatisfy(language -> assertThat(language.enabled()).isFalse());
        assertThatThrownBy(() -> service.requireRuntime("java", null).judge0LanguageId())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.UNSUPPORTED_LANGUAGE)
            );
        server.verify();
    }

    /**
     * HTTP 구현 없이도 조회 실패 후 재시도와 최신·고정 환경의 캐시 공유를 서비스에서 처리하는지 검증한다.
     */
    @Test
    @DisplayName("HTTP 구현 없이도 조회 실패 후 재시도와 최신·고정 환경의 캐시 공유를 서비스에서 처리하는지 검증한다")
    void ownsCacheAndSelectionAfterClientFailure() {
        var languageClient = mock(Judge0LanguageClient.class);
        service = new JudgeLanguageService(properties("http://judge0.test", true, false, false), languageClient);
        given(languageClient.findAll())
            .willThrow(new JudgeException(JudgeErrorCode.JUDGE0_RESPONSE_FAILED, 503))
            .willReturn(
                List
                    .of(
                        new Judge0LanguageResponse(900, "Java (JDK 17.0.6)"),
                        new Judge0LanguageResponse(910, "Java (JDK 17.0.10)")
                    )
            );
        assertThatThrownBy(() -> service.findAll())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_RESPONSE_FAILED)
            );
        var latest = service.requireRuntime("java", null);
        assertThat(latest.judge0LanguageId()).isEqualTo(910);
        var fixed = new JudgeRuntime("java", 900, "Java (JDK 17.0.6)", "17.0.6", null);
        assertThat(service.requireRuntime("java", fixed)).isSameAs(fixed);
        assertThat(service.requireRuntime("java", null)).isSameAs(latest);
        service.findAll();
        verify(languageClient, times(2)).findAll();
    }

    /**
     * 서버 주소와 활성화 정책만 설정한 언어 서비스를 생성한다.
     *
     * @param url Judge0 주소
     * @param enabled 지원 언어 활성화 여부
     * @return 테스트용 언어 서비스
     */
    private JudgeLanguageService service(String url, boolean enabled) {
        return new JudgeLanguageService(
            properties(url, enabled, enabled, enabled),
            new Judge0LanguageClient(properties(url), client)
        );
    }
}

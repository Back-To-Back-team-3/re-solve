package com.backtoback.judge.judging.client;

import static com.backtoback.judge.judging.config.Judge0TestProperties.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.backtoback.judge.judging.config.Judge0Configuration;
import com.backtoback.judge.judging.config.Judge0Properties;
import com.backtoback.judge.judging.config.Judge0Properties.Language;
import com.backtoback.judge.judging.config.Judge0Properties.Languages;
import com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;

/**
 * judge0LiveTest 작업에서만 실행하는 실제 서버 연동 테스트다.
 * JUDGE0_BASE_URL이 필요하며 인증을 사용하는 서버는 JUDGE0_AUTH_TOKEN을 지정한다.
 * 기본 언어는 python3이고 -Pjudge0Language=java 또는 cpp로 변경한다.
 * DB·Redis·LocalStack 없이 실행하며 API 호출 비용과 실행 기록이 발생할 수 있다.
 */
@Tag("judge0-live")
@EnabledIfSystemProperty(
    named = "judge0.live",
    matches = "true"
)
class Judge0LiveTest {
    private static final Logger LOG = LoggerFactory.getLogger(Judge0LiveTest.class);

    /**
     * 실제 언어 목록에서 실행 환경을 선택하고 입력 1 2를 더한 결과가 3인지 확인한다.
     * 제출은 한 번만 수행하며, 완료까지 같은 token을 최대 약 60초 동안 조회한다.
     *
     * @throws InterruptedException 대기 중 테스트가 중단된 경우
     */
    @Test
    @DisplayName("실제 언어 목록에서 실행 환경을 선택하고 입력 1 2를 더한 결과가 3인지 확인한다")
    @Timeout(90)
    void executesCodeAndReadsResult() throws InterruptedException {
        String language = System.getProperty("judge0.live.language", "python3");
        // 설정 오류는 외부 요청 전에 확인한다. 테스트 코드 이외의 사용자 코드는 전송하지 않는다.
        String code = sourceCode(language);
        String baseUrl = environment("JUDGE0_BASE_URL", "");
        assertThat(baseUrl).as("JUDGE0_BASE_URL을 실제 Judge0 주소로 지정해야 합니다.").isNotBlank();
        var configuration
            = new Judge0Configuration(
                new Judge0Properties(
                    baseUrl,
                    "X-Auth-Token",
                    environment("JUDGE0_AUTH_TOKEN", ""),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    new Languages(new Language(true), new Language(true), new Language(true))
                )
            );
        var restClient = configuration.judge0RestClient(RestClient.builder());
        var languages
            = new JudgeLanguageService(
                properties(baseUrl, language.equals("java"), language.equals("python3"), language.equals("cpp")),
                new Judge0LanguageClient(properties(baseUrl), restClient)
            );
        var client = new Judge0Client(restClient, properties(baseUrl), languages);
        // 생성 응답에서 실제 사용된 실행 환경을 확인한다. 서비스의 활성화 설정은 변경하지 않는다.
        var created
            = client.create(new JudgeExecutionCreateRequest(language, null, code, "1 2\n", 2000, 12000, 262144));
        String token = created.token();
        var runtime = created.runtime();
        assertThat(runtime.language()).isEqualTo(language);
        assertThat(runtime.judge0LanguageId()).isPositive();
        assertThat(runtime.runtimeVersion()).isNotBlank();
        JudgeExecutionResultResponse result = awaitResult(client, token);
        LOG
            .info(
                "Judge0 실제 실행 결과: language={}, runtime={}, state={}, status={}, stdout={}, time={}, memory={}",
                language,
                runtime.runtimeVersion(),
                result.state(),
                result.statusId(),
                result.stdout(),
                result.timeSeconds(),
                result.memoryKiB()
            );
        assertThat(result.state()).isEqualTo(State.COMPLETED);
        assertThat(result.statusId()).as("실행 오류 정보: %s", result).isEqualTo(3);
        assertThat(result.stdout()).isNotNull();
        assertThat(result.stdout().strip()).isEqualTo("3");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    /**
     * 대기·실행 중일 때만 동일한 실행 결과를 일정 간격으로 조회한다.
     * HTTP 실패나 실행 실패가 발생해도 코드를 다시 제출하지 않는다.
     *
     * @param client 실제 Judge0 클라이언트
     * @param token 최초 제출에서 받은 실행 식별자
     * @return 실행 종료 또는 시스템 오류 결과
     * @throws InterruptedException 대기 중 테스트가 중단된 경우
     * @throws AssertionError 제한 시간 내에 결과가 완료되지 않은 경우
     */
    private JudgeExecutionResultResponse awaitResult(Judge0Client client, String token) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            JudgeExecutionResultResponse result = client.find(token);
            if (result.state() != State.QUEUED && result.state() != State.RUNNING) {
                return result;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Judge0 실행이 60초 안에 완료되지 않았습니다. 추가 제출은 하지 않습니다.");
    }

    /**
     * 선택한 언어로 표준 입력 두 정수를 더하는 독립 실행 코드를 제공한다.
     *
     * @param language java, python3 또는 cpp
     * @return 외부 서버에 보낼 테스트 코드
     * @throws IllegalArgumentException 지원하지 않는 테스트 언어인 경우
     */
    private String sourceCode(String language) {
        return switch (language) {
            case "python3" -> "a, b = map(int, input().split())\nprint(a + b)\n";
            case "java" -> """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner input = new Scanner(System.in);
                        System.out.println(input.nextInt() + input.nextInt());
                    }
                }
                """;
            case "cpp" -> """
                #include <iostream>
                int main() {
                    int a, b;
                    std::cin >> a >> b;
                    std::cout << a + b << '\\n';
                }
                """;
            default -> throw new IllegalArgumentException("judge0Language는 java, python3, cpp 중 하나여야 합니다.");
        };
    }

    /**
     * 환경변수가 없거나 비어 있으면 기본값을 사용한다.
     *
     * @param name 환경변수 이름
     * @param defaultValue 기본값
     * @return 설정값 또는 기본값
     */
    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

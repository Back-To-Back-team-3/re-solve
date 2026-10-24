package com.backtoback.judge.judging.execution.python;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.FunctionSpec.Parameter;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;
import com.backtoback.judge.judging.service.PythonExecutionSource;

import tools.jackson.databind.json.JsonMapper;

/** 신뢰된 테스트 함수만 로컬 Python 자식 프로세스로 실행해 생성 코드를 검증한다. */
@Timeout(30)
class PythonExecutionProcessTest {
    @TempDir
    Path directory;
    private final PythonExecutionCodeGenerator generator = new PythonExecutionCodeGenerator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 정수 경계·배열·문자열·불리언을 실제 Python에서 왕복시킨다.
     *
     * @param kind 입력·반환 타입
     * @param input JSON 입력
     * @throws Exception 파일 생성 또는 실행이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INT32,2147483647",
            "INT32,-2147483648",
            "INT64,9007199254740993",
            "INT64,9223372036854775807",
            "INT64,-9223372036854775808",
            "FLOAT32,1.25",
            "FLOAT64,1.25",
            "BOOLEAN,true",
            "BOOLEAN,false"
        }
    )
    @DisplayName("Python 실행에서 공통 기본 타입과 동일 타입 배열을 보존한다")
    void echoesScalarAndArray(Kind kind, String input) throws Exception {
        echo(scalar(kind), input);
        echo(new TypeSpec(Kind.ARRAY, scalar(kind), null, null, null), "[" + input + "]");
    }

    /**
     * 문자열의 특수 문자와 서로게이트를 보존한다.
     *
     * @param input 문자열 JSON
     * @throws Exception 실행이 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "\"\"",
            "\"  한글 \\n\\\"\\\\😀  \"",
            "\"\\ud800\"",
            "\"\\udc00\""
        }
    )
    @DisplayName("Python 문자열의 공백·개행·유니코드·서로게이트를 보존한다")
    void preservesStrings(String input) throws Exception {
        echo(scalar(Kind.STRING), input);
    }

    /** 큰 입력을 소스에 삽입하지 않고 표준 입력으로 전달한다. */
    @Test
    @DisplayName("64KiB보다 큰 문자열 입력을 실행 스트림으로 전달한다")
    void handlesLargeInput() throws Exception {
        echo(scalar(Kind.STRING), mapper.writeValueAsString("a".repeat(70000)));
    }

    /**
     * 부동소수 타입의 정밀도와 실수 0의 부호를 확인한다.
     *
     * @param kind 선언 타입
     * @param input 원본 JSON
     * @param expected 실행 시 표현되는 값
     * @throws Exception 실행이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        {
            "FLOAT32,16777217,16777216",
            "FLOAT64,9007199254740993,9007199254740992",
            "FLOAT32,-0.0,-0.0",
            "FLOAT64,-0e10,-0.0"
        }
    )
    @DisplayName("Python 호출 전 선언 타입의 정밀도로 변환하고 음수 0을 유지한다")
    void preservesNumericRepresentation(Kind kind, String input, String expected) throws Exception {
        var source
            = generator.generate(spec(scalar(kind)), "def solution(value): return value", Map.of("value", input));
        var result = execute(source);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(new BigDecimal(result.stdout())).isEqualByComparingTo(expected);
        if (expected.equals("-0.0")) {
            assertThat(result.stdout()).isEqualTo("-0.0");
        }
    }

    /** 동일·위치별·반복 배열과 객체의 재귀 구조를 보존한다. */
    @Test
    @DisplayName("Python 중첩 객체·배열·혼합 배열을 순서와 타입을 유지해 실행한다")
    void handlesNestedValues() throws Exception {
        var integer = scalar(Kind.INT64);
        var matrix = new TypeSpec(Kind.ARRAY, new TypeSpec(Kind.ARRAY, integer, null, null, null), null, null, null);
        var positions
            = new TypeSpec(Kind.ARRAY, null, List.of(integer, scalar(Kind.STRING), scalar(Kind.BOOLEAN)), null, null);
        var repeated = new TypeSpec(Kind.ARRAY, null, null, List.of(scalar(Kind.STRING), integer), null);
        var object
            = new TypeSpec(
                Kind.OBJECT,
                null,
                null,
                null,
                Map.of("matrix", matrix, "positions", positions, "repeated", repeated)
            );
        echo(
            object,
            "{\"matrix\":[[9007199254740993],[]],\"positions\":[9223372036854775807,\"x\",true]"
                + ",\"repeated\":[\"a\",1,\"b\",2]}"
        );
        echo(new TypeSpec(Kind.OBJECT, null, null, null, Map.of()), "{}");
        echo(new TypeSpec(Kind.ARRAY, null, List.of(), null, null), "[]");
        echo(repeated, "[]");
    }

    /** 함수 호출 순서를 입력 객체의 키 순서와 구분한다. */
    @Test
    @DisplayName("Python 함수는 명세에 선언된 매개변수 순서대로 호출한다")
    void callsInDeclarationOrder() throws Exception {
        var function
            = new FunctionSpec(
                "solution",
                List.of(new Parameter("left", scalar(Kind.INT32)), new Parameter("right", scalar(Kind.INT32))),
                scalar(Kind.INT32)
            );
        var result
            = execute(
                generator
                    .generate(
                        function,
                        "def solution(left, right): return left - right",
                        Map.of("right", "3", "left", "10")
                    )
            );
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("7");
    }

    /** 모듈 초기화와 함수 내부 출력이 판정용 반환값을 오염시키지 않게 한다. */
    @Test
    @DisplayName("Python 모듈 초기화·함수 디버그 출력과 실행 제어 이름을 분리한다")
    void separatesDebugAndNamespace() throws Exception {
        var function = new FunctionSpec("solution", List.of(), scalar(Kind.INT32));
        String source = """
            import sys
            print("module-debug")
            _read_value = None
            _json = None
            def solution():
                print("function-debug")
                sys.stderr.write("error-debug")
                return 3
            """;
        var result = execute(generator.generate(function, source, Map.of()));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("3");
        assertThat(result.stderr()).contains("module-debug", "function-debug", "error-debug");
    }

    /**
     * 구문 오류·실행 오류·비동기 함수를 실행 실패로 남긴다.
     *
     * @param source 잘못된 사용자 소스
     * @throws Exception 실행이 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "def solution(: return 1",
            "def solution(): raise RuntimeError('failed')",
            "async def solution(): return 1",
            "def solution(): yield 1",
            "solution = 1"
        }
    )
    @DisplayName("Python 구문·실행 오류와 동기 함수가 아닌 진입점을 거절한다")
    void preservesExecutionFailure(String source) throws Exception {
        var result
            = execute(
                generator.generate(new FunctionSpec("solution", List.of(), scalar(Kind.INT32)), source, Map.of())
            );
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isNotBlank();
    }

    /**
     * JSON 직렬화 시 암묵적 변환과 비유한수를 거절한다.
     *
     * @param expression 잘못된 반환값 표현
     * @throws Exception 실행이 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "float('nan')",
            "float('inf')",
            "{1: 'x'}",
            "(1, 2)",
            "{1, 2}"
        }
    )
    @DisplayName("Python 비유한수·숫자 객체 키·지원하지 않는 반환 타입은 실행 실패다")
    void rejectsInvalidOutput(String expression) throws Exception {
        var result
            = execute(
                generator
                    .generate(
                        new FunctionSpec("solution", List.of(), scalar(Kind.INT32)),
                        "def solution(): return " + expression,
                        Map.of()
                    )
            );
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
    }

    /**
     * 명세의 값을 함수에서 그대로 반환해 JSON 보존을 검사한다.
     *
     * @param type 입력·반환 타입
     * @param input 입력 JSON
     * @throws Exception 실행이 실패한 경우
     */
    private void echo(TypeSpec type, String input) throws Exception {
        var result
            = execute(generator.generate(spec(type), "def solution(value): return value", Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(mapper.readTree(result.stdout())).isEqualTo(mapper.readTree(input));
    }

    /**
     * 신뢰된 테스트 소스를 Python 자식 프로세스로 실행하고 출력 파일을 분리한다.
     *
     * @param source 생성한 실행 소스와 입력
     * @return 종료 코드와 원시 출력
     * @throws Exception 파일 생성 또는 실행이 실패한 경우
     */
    private ProcessResult execute(PythonExecutionSource source) throws Exception {
        Path file = directory.resolve("main.py");
        Path stdin = directory.resolve("stdin");
        Path stdout = directory.resolve("stdout");
        Path stderr = directory.resolve("stderr");
        Files.writeString(file, source.sourceCode());
        Files.writeString(stdin, source.stdin());
        Process process
            = new ProcessBuilder("python3", "-I", file.toString())
                .redirectInput(stdin.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            return new ProcessResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * 단일 인자 함수 명세를 만든다.
     *
     * @param type 입력·반환 타입
     * @return 함수 명세
     */
    private FunctionSpec spec(TypeSpec type) {
        return new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
    }

    /**
     * 기본 타입 명세를 만든다.
     *
     * @param kind 타입 종류
     * @return 타입 명세
     */
    private TypeSpec scalar(Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }

    /** 테스트 프로세스의 종료 코드와 분리된 출력이다. */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}

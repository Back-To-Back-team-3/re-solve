package com.backtoback.judge.judging.service.execution.cpp;

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
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;

import tools.jackson.databind.json.JsonMapper;

/** 신뢰된 테스트 소스만 C++17로 컴파일하고 자식 프로세스에서 실행한다. */
@Timeout(60)
class CppExecutionProcessTest {
    @TempDir
    Path directory;
    private final ExecutionCodeGenerator generator = new CppExecutionCodeGenerator();
    private final CppTypeMapper types = new CppTypeMapper();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 선언 폭을 검사한 기본형 정수·실수·불리언을 실제 C++에서 왕복시킨다.
     *
     * @param kind 선언 타입
     * @param input JSON 입력
     * @throws Exception 컴파일 또는 실행이 실패한 경우
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
    @DisplayName("C++ 기본 타입은 숫자 정밀도와 JSON 타입을 유지한다")
    void echoesScalar(Kind kind, String input) throws Exception {
        echo(scalar(kind), input);
    }

    /**
     * UTF-8 문자열과 UTF-16 단독 서로게이트를 보존한다.
     *
     * @param input 문자열 JSON
     * @throws Exception 컴파일 또는 실행이 실패한 경우
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
    @DisplayName("C++ 문자열의 공백·제어 문자·유니코드·서로게이트를 보존한다")
    void preservesStrings(String input) throws Exception {
        echo(scalar(Kind.STRING), input);
    }

    /**
     * 선언 타입의 반올림과 실수 0의 부호를 확인한다.
     *
     * @param kind 부동소수 타입
     * @param input 원본 JSON
     * @param expected 실행에서 표현되는 값
     * @throws Exception 컴파일 또는 실행이 실패한 경우
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
    @DisplayName("C++ 함수 호출 전 선언 타입의 정밀도로 변환하고 음수 0을 유지한다")
    void preservesNumericRepresentation(Kind kind, String input, String expected) throws Exception {
        var type = scalar(kind);
        var result = execute(generator.generate(spec(type), echoSource(type), Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(new BigDecimal(result.stdout())).isEqualByComparingTo(expected);
        if (expected.equals("-0.0")) {
            assertThat(result.stdout()).isEqualTo("-0.0");
        }
    }

    /** 동일 타입 배열과 혼합 배열·객체를 중첩해 전달한다. */
    @Test
    @DisplayName("C++ 중첩 배열·객체·위치별·반복 배열을 Value로 보존한다")
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
            "{\"matrix\":[[9007199254740993],[]],\"positions\":[9223372036854775807,\"x\",true],"
                + "\"repeated\":[\"a\",1,\"b\",2]}"
        );
        echo(matrix, "[[1,2],[],[-3]]");
        echo(repeated, "[]");
        echo(new TypeSpec(Kind.ARRAY, null, List.of(), null, null), "[]");
        echo(new TypeSpec(Kind.OBJECT, null, null, null, Map.of()), "{}");
    }

    /**
     * 혼합 배열의 기본 숫자 타입을 그대로 읽고 다시 저장한다.
     *
     * @throws Exception 컴파일 또는 실행이 실패한 경우
     */
    @Test
    @DisplayName("C++ Value가 int·long long·float·double을 구분하여 복원하고 직렬화한다")
    void preservesBasicNumericAlternatives() throws Exception {
        var members = List.of(scalar(Kind.INT32), scalar(Kind.INT64), scalar(Kind.FLOAT32), scalar(Kind.FLOAT64));
        var type = new TypeSpec(Kind.ARRAY, null, members, null, null);
        String source = """
            std::vector<Value> solution(const std::vector<Value>& value) {
                return {std::get<int>(value[0].data), std::get<long long>(value[1].data),
                        std::get<float>(value[2].data), std::get<double>(value[3].data)};
            }
            """;
        String input = "[-2147483648,9223372036854775807,1.25,2.5]";
        var result = execute(generator.generate(spec(type), source, Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(mapper.readTree(result.stdout())).isEqualTo(mapper.readTree(input));
    }

    /** vector<bool>의 프록시와 객체 배열의 직렬화를 검증한다. */
    @Test
    @DisplayName("C++ 불리언 벡터와 고정 필드 객체 벡터를 실행한다")
    void handlesTypedVectors() throws Exception {
        echo(new TypeSpec(Kind.ARRAY, scalar(Kind.BOOLEAN), null, null, null), "[true,false,true]");
        var object = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("n", scalar(Kind.INT32)));
        echo(new TypeSpec(Kind.ARRAY, object, null, null, null), "[{\"n\":1},{\"n\":2}]");
    }

    /** Value에 담긴 원소를 표준 variant API로 읽고 새 결과를 구성한다. */
    @Test
    @DisplayName("C++ 사용자 함수에서 Value의 선언 타입을 읽고 객체·배열을 구성한다")
    void constructsAndReadsValues() throws Exception {
        var positions = new TypeSpec(Kind.ARRAY, null, List.of(scalar(Kind.INT64), scalar(Kind.STRING)), null, null);
        var output
            = new TypeSpec(
                Kind.OBJECT,
                null,
                null,
                null,
                Map
                    .of(
                        "n",
                        scalar(Kind.INT64),
                        "s",
                        scalar(Kind.STRING),
                        "items",
                        new TypeSpec(Kind.ARRAY, scalar(Kind.INT32), null, null, null)
                    )
            );
        var function = new FunctionSpec("solution", List.of(new Parameter("value", positions)), output);
        String source = """
            std::map<std::string, Value> solution(const std::vector<Value>& value) {
                return {{"n", std::get<long long>(value[0].data)},
                        {"s", std::get<std::string>(value[1].data)},
                        {"items", std::vector<int>{1,2}}};
            }
            """;
        var result = execute(generator.generate(function, source, Map.of("value", "[9007199254740993,\"x\"]")));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(mapper.readTree(result.stdout()))
            .isEqualTo(mapper.readTree("{\"n\":9007199254740993,\"s\":\"x\",\"items\":[1,2]}"));
    }

    /** 모듈 초기화와 cout·printf 출력이 반환 JSON을 오염시키지 않게 한다. */
    @Test
    @DisplayName("C++ 전역 초기화·cout·printf 디버그 출력을 반환값과 분리한다")
    void separatesDebugOutput() throws Exception {
        String source = """
            int initialize() { std::printf("global-debug\\n"); return 0; }
            int initialized = initialize();
            int solution() {
                std::cout << "cout-debug";
                std::printf("printf-debug");
                std::cerr << "cerr-debug";
                return 3;
            }
            """;
        var result
            = execute(
                generator.generate(new FunctionSpec("solution", List.of(), scalar(Kind.INT32)), source, Map.of())
            );
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("3");
        assertThat(result.stderr()).contains("global-debug", "cout-debug", "printf-debug", "cerr-debug");
    }

    /** 함수 인자는 명세의 선언 순서대로 전달한다. */
    @Test
    @DisplayName("C++ 함수에 매개변수 선언 순서대로 인자를 전달한다")
    void preservesArgumentOrder() throws Exception {
        var integer = scalar(Kind.INT32);
        var function
            = new FunctionSpec(
                "solution",
                List.of(new Parameter("left", integer), new Parameter("right", integer)),
                integer
            );
        var result
            = execute(
                generator
                    .generate(
                        function,
                        "int solution(int left, int right) { return left - right; }",
                        Map.of("right", "3", "left", "10")
                    )
            );
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("7");
    }

    /** 기본 코드의 Value 선언이 실행 템플릿과 중복되지 않게 한다. */
    @Test
    @DisplayName("생성한 C++ 기본 코드를 실행 템플릿에 다시 넣어도 컴파일된다")
    void compilesStarter() throws Exception {
        var type = new TypeSpec(Kind.ARRAY, null, List.of(scalar(Kind.INT32), scalar(Kind.STRING)), null, null);
        var function = spec(type);
        var source = generator.generate(function, generator.starterCode(function), Map.of("value", "[1,\"a\"]"));
        assertThat(compile(source).exitCode()).isZero();
    }

    /**
     * 사용자 함수의 구문·타입 오류를 컴파일 실패로 남긴다.
     *
     * @param source 잘못된 C++ 코드
     * @throws Exception 컴파일이 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "int solution(int value) { return ; }",
            "double solution(int value) { return value; }",
            "int solution(double value) { return value; }",
            "int main() { return 0; }"
        }
    )
    @DisplayName("C++ 구문 오류와 선언 타입의 암묵적 변환·사용자 main을 컴파일에서 거절한다")
    void rejectsCompileFailures(String source) throws Exception {
        var result = compile(generator.generate(spec(scalar(Kind.INT32)), source, Map.of("value", "1")));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).isNotBlank();
    }

    /**
     * 크기가 같은 정수 타입과 실수 타입도 선언한 기본형과 다르면 거절한다.
     *
     * @param kind 함수 명세의 입력·반환 타입
     * @param source 다른 기본형을 선언한 사용자 함수
     * @throws Exception 컴파일이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "INT32|int solution(long long value) { return value; }",
            "INT32|long long solution(int value) { return value; }",
            "INT64|long long solution(long value) { return value; }",
            "INT64|long solution(long long value) { return value; }",
            "FLOAT32|float solution(double value) { return value; }",
            "FLOAT32|double solution(float value) { return value; }",
            "FLOAT64|double solution(float value) { return value; }",
            "FLOAT64|float solution(double value) { return value; }"
        }
    )
    @DisplayName("C++ 함수의 인자·반환 기본형이 다르면 암묵적 수치 변환 대신 컴파일을 거절한다")
    void rejectsDifferentNumericSignature(Kind kind, String source) throws Exception {
        var result = compile(generator.generate(spec(scalar(kind)), source, Map.of("value", "1")));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("Incorrect solution signature");
    }

    /**
     * 예외·비유한수·잘못된 문자열을 실행 실패로 남긴다.
     *
     * @param expression 반환할 수 없는 float 표현
     * @throws Exception 컴파일 또는 실행이 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "std::numeric_limits<float>::infinity()",
            "std::numeric_limits<float>::quiet_NaN()",
            "throw std::runtime_error(\"failed\")"
        }
    )
    @DisplayName("C++ 예외와 NaN·Infinity 반환은 실행 실패다")
    void preservesRuntimeFailure(String expression) throws Exception {
        var function = new FunctionSpec("solution", List.of(), scalar(Kind.FLOAT32));
        String body = expression.startsWith("throw") ? expression + ";" : "return " + expression + ";";
        var result = execute(generator.generate(function, "float solution() { " + body + " }", Map.of()));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
    }

    /**
     * 값을 그대로 반환하고 JSON 구조를 비교한다.
     *
     * @param type 입력·반환 타입
     * @param input 입력 JSON
     * @throws Exception 컴파일 또는 실행이 실패한 경우
     */
    private void echo(TypeSpec type, String input) throws Exception {
        var result = execute(generator.generate(spec(type), echoSource(type), Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(mapper.readTree(result.stdout())).isEqualTo(mapper.readTree(input));
    }

    /**
     * 동일 타입 인자를 그대로 반환할 C++ 함수를 만든다.
     *
     * @param type 입력·반환 타입
     * @return 테스트 소스
     */
    private String echoSource(TypeSpec type) {
        String cppType = types.sourceType(type);
        return cppType + " solution(" + cppType + " value) { return value; }";
    }

    /**
     * 실제 표준 라이브러리만 사용해 C++17로 컴파일한다.
     *
     * @param source 생성 소스
     * @return 컴파일 종료 코드와 메시지
     * @throws Exception 파일 생성 또는 컴파일이 실패한 경우
     */
    private ProcessResult compile(ExecutionSource source) throws Exception {
        Path file = directory.resolve("main.cpp");
        Path log = directory.resolve("compile.log");
        Files.writeString(file, source.sourceCode());
        Process process
            = new ProcessBuilder(
                "c++",
                "-std=c++17",
                "-O0",
                file.toString(),
                "-o",
                directory.resolve("main").toString()
            ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            return new ProcessResult(process.exitValue(), "", Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * 생성한 프로그램에 실행 입력을 전달하고 출력과 종료 코드를 읽는다.
     *
     * @param source 실행 소스와 입력
     * @return 프로세스 실행 결과
     * @throws Exception 컴파일 또는 실행이 실패한 경우
     */
    private ProcessResult execute(ExecutionSource source) throws Exception {
        var compilation = compile(source);
        assertThat(compilation.exitCode()).as(compilation.stderr()).isZero();
        Path stdin = directory.resolve("stdin");
        Path stdout = directory.resolve("stdout");
        Path stderr = directory.resolve("stderr");
        Files.writeString(stdin, source.stdin());
        Process process
            = new ProcessBuilder(directory.resolve("main").toString())
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
     * 단일 인자의 함수 명세를 만든다.
     *
     * @param type 입력·반환 타입
     * @return 함수 명세
     */
    private FunctionSpec spec(TypeSpec type) {
        return new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
    }

    /**
     * 기본 타입을 만든다.
     *
     * @param kind 타입 종류
     * @return 타입 명세
     */
    private TypeSpec scalar(Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }

    /** 컴파일 또는 실행 프로세스 결과다. */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}

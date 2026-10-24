package com.backtoback.judge.judging.execution.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.FunctionSpec.Parameter;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;
import com.backtoback.judge.judging.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.ExecutionSource;

import tools.jackson.databind.json.JsonMapper;

/** 저장소의 신뢰된 테스트 코드만 별도 JVM에서 실행한다. Judge0 샌드박스 검증을 대신하지 않는다. */
@Timeout(30)
class JavaExecutionProcessTests {
    @TempDir
    Path directory;
    private final ExecutionCodeGenerator generator = new JavaExecutionCodeGenerator();
    private final JavaTypeMapper types = new JavaTypeMapper();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 원본 정수 JSON이 선언한 부동소수 타입으로 반올림된 뒤 함수에 전달되는지 확인한다.
     *
     * @param kind 선언한 부동소수 타입
     * @param input 정밀도 경계 밖의 입력 JSON
     * @param normalized 선언 타입에서 표현되는 숫자 값
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        {
            "FLOAT32,16777217,16777216",
            "FLOAT64,9007199254740993,9007199254740992"
        }
    )
    @DisplayName("함수 입력과 반환 직렬화는 선언한 부동소수 타입의 정밀도를 따른다")
    void roundsAtDeclaredFloatingPointPrecision(Kind kind, String input, String normalized) throws Exception {
        var type = scalar(kind);
        var function = new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
        String javaType = types.sourceType(type);
        String source = "class Solution { public " + javaType + " solution(" + javaType + " value) { return value; } }";
        var result = execute(generator.generate(function, source, Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(new BigDecimal(result.stdout())).isEqualByComparingTo(normalized);
    }

    /**
     * 실제 컴파일·실행으로 기본 타입의 경계값을 확인한다.
     *
     * @param kind 입력·반환 타입
     * @param input JSON 입력
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INT32,-2147483648",
            "INT32,2147483647",
            "INT64,9007199254740993",
            "INT64,9223372036854775807",
            "INT64,-9223372036854775808",
            "FLOAT64,1.25",
            "BOOLEAN,true"
        }
    )
    @DisplayName("Java 17로 컴파일한 코드가 기본 타입의 값과 정밀도를 보존한다")
    void roundTripsScalar(Kind kind, String input) throws Exception {
        assertEcho(scalar(kind), input);
    }

    /**
     * 입력이 실제 {@code float}로 전달되고 반환값이 유효한 JSON 숫자로 출력되는지 확인한다.
     *
     * @param input 입력 JSON 숫자
     * @param expected 32비트 반올림 후의 출력
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        {
            "1.25,1.25",
            "0.1,0.1",
            "16777217,1.6777216E7",
            "3.4028235e38,3.4028235E38",
            "-3.4028235e38,-3.4028235E38",
            "1.4e-45,1.4E-45",
            "-1.4e-45,-1.4E-45",
            "0,0.0"
        }
    )
    @DisplayName("FLOAT32 입력을 float로 반올림하고 경계값과 0을 실행한다")
    void executesFloat32(String input, String expected) throws Exception {
        var type = scalar(Kind.FLOAT32);
        var spec = new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
        var result
            = execute(
                generator
                    .generate(
                        spec,
                        "class Solution { public float solution(float value) { return value; } }",
                        Map.of("value", input)
                    )
            );
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo(expected);
        assertThat(mapper.readTree(result.stdout()).isNumber()).isTrue();
    }

    /**
     * 실수 입력의 부호가 사용자 함수와 JSON 반환값까지 보존되는지 확인한다.
     *
     * @param kind 입력·반환 타입
     * @param input 지수 표기를 포함한 음수 0
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @ParameterizedTest
    @CsvSource(
        {
            "FLOAT32,-0.0",
            "FLOAT32,-0.00",
            "FLOAT32,-0e10",
            "FLOAT64,-0.0",
            "FLOAT64,-0.00",
            "FLOAT64,-0e10"
        }
    )
    @DisplayName("FLOAT32·FLOAT64의 음수 0을 입력부터 반환 출력까지 보존한다")
    void preservesNegativeZero(Kind kind, String input) throws Exception {
        var type = scalar(kind);
        var spec = new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
        String javaType = types.sourceType(type);
        String source = "class Solution { public " + javaType + " solution(" + javaType + " value) { return value; } }";
        var result = execute(generator.generate(spec, source, Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("-0.0");
    }

    /**
     * 중첩 구조 안의 양수·음수 0이 실제 계산에서도 구분되는지 확인한다.
     *
     * @param kind 배열·객체 안의 실수 타입
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @ParameterizedTest
    @EnumSource(
        value = Kind.class,
        names = {
            "FLOAT32",
            "FLOAT64"
        }
    )
    @DisplayName("객체·중첩 배열·위치·반복 배열에서 0의 부호를 유지한다")
    void preservesNestedSignedZeros(Kind kind) throws Exception {
        var type = scalar(kind);
        var array = new TypeSpec(Kind.ARRAY, type, null, null, null);
        var matrix = new TypeSpec(Kind.ARRAY, array, null, null, null);
        var positions = new TypeSpec(Kind.ARRAY, null, List.of(type, scalar(Kind.STRING)), null, null);
        var repeated = new TypeSpec(Kind.ARRAY, null, null, List.of(type, scalar(Kind.INT32)), null);
        var object
            = new TypeSpec(
                Kind.OBJECT,
                null,
                null,
                null,
                Map.of("matrix", matrix, "positions", positions, "repeated", repeated)
            );
        var returns = new TypeSpec(Kind.ARRAY, scalar(Kind.FLOAT64), null, null, null);
        var spec = new FunctionSpec("solution", List.of(new Parameter("value", object)), returns);
        String javaType = types.sourceType(matrix);
        String source = """
            class Solution {
                public double[] solution(java.util.Map<String, Object> value) {
                    var matrix = (%s) value.get("matrix");
                    var positions = (Object[]) value.get("positions");
                    var repeated = (Object[]) value.get("repeated");
                    return new double[] {
                        Math.copySign(1.0, matrix[0][0]),
                        Math.copySign(1.0, matrix[0][1]),
                        Math.copySign(1.0, ((Number) positions[0]).doubleValue()),
                        Math.copySign(1.0, ((Number) repeated[0]).doubleValue()),
                        Math.copySign(1.0, ((Number) repeated[2]).doubleValue())
                    };
                }
            }
            """.formatted(javaType);
        String input = """
            {"matrix":[[-0.0,0.0]],"positions":[-0e10,"-0.0"],"repeated":[-0.00,1,0.0,2]}
            """;
        var result = execute(generator.generate(spec, source, Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("[-1.0,1.0,-1.0,-1.0,1.0]");
    }

    /**
     * 중첩 배열의 원시 {@code float} 타입과 혼합 구조 안의 {@code Float} 값을 복원한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("float[][]와 객체·위치·반복 배열에 포함된 FLOAT32를 실행한다")
    void executesNestedFloat32() throws Exception {
        var float32 = scalar(Kind.FLOAT32);
        var floats = new TypeSpec(Kind.ARRAY, float32, null, null, null);
        assertEcho(new TypeSpec(Kind.ARRAY, floats, null, null, null), "[[1.25,-2.5],[],[0.0]]");
        var positions = new TypeSpec(Kind.ARRAY, null, List.of(float32, scalar(Kind.STRING)), null, null);
        var repeated = new TypeSpec(Kind.ARRAY, null, null, List.of(float32, scalar(Kind.INT32)), null);
        var object
            = new TypeSpec(
                Kind.OBJECT,
                null,
                null,
                null,
                Map.of("value", float32, "positions", positions, "repeated", repeated)
            );
        assertEcho(object, "{\"value\":0.1,\"positions\":[1.25,\"a\"],\"repeated\":[2.5,1,-3.75,2]}");
    }

    /**
     * 계산 결과의 비정상 실수가 정답 출력으로 직렬화되지 않도록 확인한다.
     *
     * @param expression 비정상 {@code float} 값을 반환하는 식
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "Float.NaN",
            "Float.POSITIVE_INFINITY",
            "Float.NEGATIVE_INFINITY"
        }
    )
    @DisplayName("FLOAT32의 NaN과 무한대 반환은 실행 실패로 남는다")
    void rejectsNonFiniteFloatReturn(String expression) throws Exception {
        var spec = new FunctionSpec("solution", List.of(), scalar(Kind.FLOAT32));
        var result
            = execute(
                generator
                    .generate(
                        spec,
                        "class Solution { public float solution() { return " + expression + "; } }",
                        Map.of()
                    )
            );
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("IllegalArgumentException");
    }

    /**
     * 입력의 문자열이 소스에 삽입되지 않고 그대로 왕복하는지 확인한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("문자열의 공백·제어 문자·유니코드와 큰 입력을 보존한다")
    void roundTripsLargeString() throws Exception {
        String text
            = "  \"\\\n\t\r\b\f" + (char)0 + " 한글 😀 " + '\\' + "u000a ${ARGUMENTS}; System.exit(1); "
                + "가".repeat(70000);
        assertEcho(scalar(Kind.STRING), mapper.writeValueAsString(text));
    }

    /**
     * 배열의 실제 Java 타입과 빈 배열·중첩 순서를 확인한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("중첩 int 배열은 int[][]로 복원되고 빈 배열도 보존한다")
    void roundTripsPrimitiveArrays() throws Exception {
        var integers = new TypeSpec(Kind.ARRAY, scalar(Kind.INT32), null, null, null);
        assertEcho(new TypeSpec(Kind.ARRAY, integers, null, null, null), "[[1,2],[],[-3]]");
    }

    /**
     * 혼합 배열과 객체를 함께 사용하는 실행을 확인한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("위치별 배열과 객체의 중첩 입력을 Object[]와 Map으로 복원한다")
    void roundTripsMixedObject() throws Exception {
        var nested = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("n", scalar(Kind.INT64)));
        var positions
            = new TypeSpec(Kind.ARRAY, null, List.of(scalar(Kind.INT32), scalar(Kind.STRING), nested), null, null);
        var object
            = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("items", positions, "enabled", scalar(Kind.BOOLEAN)));
        assertEcho(object, "{\"enabled\":true,\"items\":[1,\"a\",{\"n\":9007199254740993}]}");
    }

    /**
     * 반복 패턴의 값 위치와 boxed 타입을 확인한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("반복 패턴 배열의 각 원소 타입과 순서를 보존한다")
    void roundTripsRepeatPattern() throws Exception {
        var repeated = new TypeSpec(Kind.ARRAY, null, null, List.of(scalar(Kind.INT64), scalar(Kind.STRING)), null);
        assertEcho(repeated, "[9007199254740993,\"a\",-1,\"b\"]");
    }

    /**
     * {@code import}와 인자 선언 순서를 유지하며 디버그 출력이 결과에 섞이지 않게 한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("디버그 출력은 stderr로 분리하고 실제 반환값만 stdout으로 받는다")
    void separatesDebugOutput() throws Exception {
        var spec
            = new FunctionSpec(
                "solution",
                List.of(new Parameter("a", scalar(Kind.INT32)), new Parameter("b", scalar(Kind.INT32))),
                scalar(Kind.INT32)
            );
        var code = generator.generate(spec, """
            import java.util.List;
            class Solution {
                public int solution(int a, int b) {
                    System.out.println("999");
                    System.err.println("debug");
                    return List.of(a, b).get(0) - b;
                }
            }
            """, Map.of("b", "3", "a", "10"));
        var result = execute(code);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("7");
        assertThat(result.stderr()).contains("999", "debug");
    }

    /**
     * 매개변수가 없는 함수와 비공개 {@code Solution} 클래스를 실행한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("인자가 없는 함수도 빈 입력으로 실행한다")
    void executesWithoutParameters() throws Exception {
        var spec = new FunctionSpec("solution", List.of(), scalar(Kind.INT32));
        var result
            = execute(generator.generate(spec, "class Solution { public int solution() { return 42; } }", Map.of()));
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("42");
    }

    /**
     * 문법 오류를 수정하거나 성공 결과로 바꾸지 않는다.
     *
     * @throws Exception 임시 파일을 만들지 못한 경우
     */
    @Test
    @DisplayName("사용자 문법 오류는 컴파일 실패로 남는다")
    void preservesCompileError() throws Exception {
        var spec = new FunctionSpec("solution", List.of(), scalar(Kind.INT32));
        var source = generator.generate(spec, "class Solution { public int solution() { return ; } }", Map.of());
        assertThat(compile(source).exitCode()).isNotZero();
    }

    /**
     * 사용자 예외가 반환값을 만들지 않는지 확인한다.
     *
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    @Test
    @DisplayName("사용자 함수의 예외는 실행 실패와 빈 반환 출력으로 남는다")
    void preservesExecutionError() throws Exception {
        var spec = new FunctionSpec("solution", List.of(), scalar(Kind.INT32));
        var result
            = execute(
                generator
                    .generate(
                        spec,
                        "class Solution { public int solution() { throw new IllegalStateException(); } }",
                        Map.of()
                    )
            );
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("IllegalStateException");
    }

    /**
     * 값을 그대로 반환하는 함수를 생성해 타입·중첩 구조를 비교한다.
     *
     * @param type 입력·반환 명세
     * @param input JSON 입력
     * @throws Exception 컴파일·실행이 실패한 경우
     */
    private void assertEcho(TypeSpec type, String input) throws Exception {
        var spec = new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
        String source
            = "class Solution { public " + types.sourceType(type) + " solution(" + types.sourceType(type)
                + " value) { return value; } }";
        var result = execute(generator.generate(spec, source, Map.of("value", input)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(mapper.readTree(result.stdout())).isEqualTo(mapper.readTree(input));
    }

    /**
     * Java 17 호환 클래스로 컴파일한다. 실행 클래스패스에 외부 라이브러리를 추가하지 않는다.
     *
     * @param code 생성된 소스
     * @return 컴파일 상태와 메시지
     * @throws Exception 파일 생성 또는 컴파일 준비가 실패한 경우
     */
    private ProcessResult compile(ExecutionSource code) throws Exception {
        Path file = directory.resolve("Main.java");
        Files.writeString(file, code.sourceCode());
        var output = new ByteArrayOutputStream();
        int status
            = ToolProvider
                .getSystemJavaCompiler()
                .run(
                    null,
                    output,
                    output,
                    "--release",
                    "17",
                    "-encoding",
                    "UTF-8",
                    "-d",
                    directory.toString(),
                    file.toString()
                );
        return new ProcessResult(status, "", output.toString(StandardCharsets.UTF_8));
    }

    /**
     * 테스트 전용 코드를 자식 JVM에서 실행하고 프로세스가 남지 않도록 정리한다.
     *
     * @param code 생성 코드와 입력
     * @return 종료 코드와 분리한 출력
     * @throws Exception 컴파일·프로세스 실행·입출력이 실패한 경우
     */
    private ProcessResult execute(ExecutionSource code) throws Exception {
        var compilation = compile(code);
        assertThat(compilation.exitCode()).as(compilation.stderr()).isZero();
        Path stdin = directory.resolve("stdin");
        Path stdout = directory.resolve("stdout");
        Path stderr = directory.resolve("stderr");
        Files.writeString(stdin, code.stdin());
        Process process
            = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                directory.toString(),
                "Main"
            ).redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("테스트 코드가 제한 시간 안에 종료되어야 합니다.").isTrue();
            return new ProcessResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * 기본 타입 명세를 만든다.
     *
     * @param kind 기본 타입
     * @return 타입 명세
     */
    private TypeSpec scalar(Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}

package com.backtoback.judge.judging.service.execution.cpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.FunctionSpec.Parameter;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/** C++ 기본 코드·공통 입력 계약·기존 실행 요청 연결을 검증한다. */
class CppExecutionCodeGeneratorTest {
    private final CppExecutionCodeGenerator generator = new CppExecutionCodeGenerator();

    /**
     * 긴 숫자 표기도 선언 정밀도로 변환하고 중첩 구조에서 같은 입력 스트림을 만든다.
     *
     * @param kind 선언 부동소수 타입
     */
    @ParameterizedTest
    @EnumSource(
        value = Kind.class,
        names = {
            "FLOAT32",
            "FLOAT64"
        }
    )
    @DisplayName("긴 소수와 짧은 소수는 기본 값과 중첩 객체에서 같은 실행 입력으로 변환한다")
    void encodesLongDecimals(Kind kind) {
        var type = scalar(kind);
        String decimal = "1." + "0".repeat(1200);
        String source = "double solution(double value) { return value; }";
        assertThat(generator.generate(spec(type), source, Map.of("value", decimal)).stdin())
            .isEqualTo(generator.generate(spec(type), source, Map.of("value", "1.0")).stdin());
        var array = new TypeSpec(Kind.ARRAY, type, null, null, null);
        var object = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("items", array));
        assertThat(generator.generate(spec(object), source, Map.of("value", "{\"items\":[" + decimal + "]}")).stdin())
            .isEqualTo(generator.generate(spec(object), source, Map.of("value", "{\"items\":[1.0]}")).stdin());
    }

    /**
     * 숫자 길이 제한을 조정해도 선언 타입의 범위 초과와 언더플로는 거절한다.
     *
     * @param kind 선언 부동소수 타입
     */
    @ParameterizedTest
    @EnumSource(
        value = Kind.class,
        names = {
            "FLOAT32",
            "FLOAT64"
        }
    )
    @DisplayName("긴 숫자라도 선언 타입의 범위 초과와 언더플로는 거절한다")
    void rejectsOutOfRangeLongDecimals(Kind kind) {
        String decimal = "1." + "0".repeat(1200);
        for (String exponent : List.of("e400", "e-400")) {
            assertThatThrownBy(
                () -> generator
                    .generate(
                        spec(scalar(kind)),
                        "double solution(double value) { return value; }",
                        Map.of("value", decimal + exponent)
                    )
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 표준 C++ 타입 이름를 생성한다.
     *
     * @param kind 선언 타입
     * @param hint C++ 타입 이름
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INT32,int",
            "INT64,long long",
            "FLOAT32,float",
            "FLOAT64,double",
            "STRING,std::string",
            "BOOLEAN,bool"
        }
    )
    @DisplayName("기본 타입과 배열·객체의 C++ 기본 코드를 생성한다")
    void createsStarter(Kind kind, String hint) {
        String cppType = hint.startsWith("std::") ? "::" + hint : hint;
        assertThat(generator.starterCode(spec(scalar(kind)))).contains("solution(" + cppType + " value)");
        assertThat(generator.starterCode(spec(new TypeSpec(Kind.ARRAY, scalar(kind), null, null, null))))
            .contains("::std::vector<" + cppType + "> value");
        assertThat(generator.starterCode(spec(new TypeSpec(Kind.OBJECT, null, null, null, Map.of()))))
            .contains("::std::map<::std::string, ::Value> value");
    }

    /**
     * 두 생성 경로에서 C++ 예약어를 거절한다.
     *
     * @param name 잘못된 매개변수 이름
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "class",
            "template",
            "namespace",
            "int",
            "true",
            "false",
            "nullptr",
            "__value",
            "_Reserved"
        }
    )
    @DisplayName("C++ 예약어를 매개변수 이름으로 허용하지 않는다")
    void rejectsKeywords(String name) {
        var type = scalar(Kind.INT32);
        var function = new FunctionSpec("solution", List.of(new Parameter(name, type)), type);
        assertThatThrownBy(() -> generator.starterCode(function)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(function, "int solution(int x) { return x; }", Map.of(name, "1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 실행 환경·제한값을 기존 요청 모델에 연결한다. */
    @Test
    @DisplayName("C++ 실행 결과를 기존 JudgeClient 요청에 연결한다")
    void bridgesToRequest() {
        var source
            = generator
                .generate(
                    spec(scalar(Kind.INT64)),
                    "long long solution(long long value) { return value; }",
                    Map.of("value", "1")
                );
        var runtime = new JudgeRuntime("cpp", 54, "C++ (GCC 12.2.0)", "12.2.0", "-std=c++17");
        assertThat(source.language()).isEqualTo(JudgeLanguage.CPP);
        var request = source.toRequest(runtime, 2000, 7000, 262144);
        assertThat(request.language()).isEqualTo("cpp");
        assertThat(request.fixedRuntime()).isSameAs(runtime);
        assertThat(request.sourceCode()).isEqualTo(source.sourceCode());
        assertThat(request.stdin()).isEqualTo(source.stdin());
        assertThat(request.cpuTimeMs()).isEqualTo(2000);
        assertThat(request.wallTimeMs()).isEqualTo(7000);
        assertThat(request.memoryKiB()).isEqualTo(262144);
        assertThat(source.toRequest(null, 2000, 7000, 262144).fixedRuntime()).isNull();
        assertThatThrownBy(() -> source.toRequest(new JudgeRuntime("java", 91, "Java", "17", null), 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 입력 객체의 순서나 값이 생성 소스에 영향을 주지 않는지 확인한다. */
    @Test
    @DisplayName("입력은 소스에 삽입하지 않고 매개변수 선언 순서대로 인코딩한다")
    void separatesInput() {
        var function
            = new FunctionSpec(
                "solution",
                List.of(new Parameter("a", scalar(Kind.INT32)), new Parameter("b", scalar(Kind.STRING))),
                scalar(Kind.STRING)
            );
        var reverse = new LinkedHashMap<String, String>();
        reverse.put("b", "\"secret-input\"");
        reverse.put("a", "1");
        String source = "std::string solution(int a, std::string b) { return b; }";
        var first = generator.generate(function, source, reverse);
        assertThat(first).isEqualTo(generator.generate(function, source, Map.of("a", "1", "b", "\"secret-input\"")));
        assertThat(first.sourceCode()).doesNotContain("secret-input");
        assertThat(first.sourceCode())
            .isEqualTo(generator.generate(function, source, Map.of("a", "2", "b", "\"other\"")).sourceCode());
    }

    /**
     * 정수의 형식과 범위를 검사한다.
     *
     * @param input 잘못된 JSON 입력
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "null",
            "true",
            "\"1\"",
            "1.0",
            "2147483648",
            "-2147483649",
            "[]",
            "{}",
            "1 2",
            "NaN",
            "[1"
        }
    )
    @DisplayName("정수 입력의 JSON 형식·타입·범위를 엄격히 검사한다")
    void rejectsInvalidInt(String input) {
        assertThatThrownBy(
            () -> generator
                .generate(spec(scalar(Kind.INT32)), "int solution(int value) { return value; }", Map.of("value", input))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 64비트 정수 범위를 검사한다.
     *
     * @param input 범위를 벗어난 정수
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "9223372036854775808",
            "-9223372036854775809"
        }
    )
    @DisplayName("INT64 범위를 벗어난 입력을 거절한다")
    void rejectsLongOverflow(String input) {
        assertThatThrownBy(
            () -> generator
                .generate(
                    spec(scalar(Kind.INT64)),
                    "long long solution(long long value) { return value; }",
                    Map.of("value", input)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 직접 선언 타입으로 변환할 때 무한대와 0으로 소실되는 입력을 거절한다.
     *
     * @param kind 부동소수 타입
     * @param input 잘못된 입력
     */
    @ParameterizedTest
    @CsvSource(
        {
            "FLOAT32,3.5e38",
            "FLOAT32,1e-50",
            "FLOAT64,1e400",
            "FLOAT64,1e-4000"
        }
    )
    @DisplayName("부동소수의 범위 초과와 0으로의 언더플로를 거절한다")
    void rejectsFloatingOverflow(Kind kind, String input) {
        assertThatThrownBy(
            () -> generator
                .generate(spec(scalar(kind)), "int solution(int value) { return value; }", Map.of("value", input))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /** 잘못된 함수 구조와 재귀 타입을 외부 실행 전에 거절한다. */
    @Test
    @DisplayName("함수·타입·입력 이름이 잘못되면 실행 코드를 만들지 않는다")
    void rejectsMalformedSpecification() {
        var type = scalar(Kind.INT32);
        assertThatThrownBy(() -> generator.starterCode(new FunctionSpec("other", List.of(), type)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
            () -> generator
                .starterCode(
                    new FunctionSpec("solution", List.of(new Parameter("a", type), new Parameter("a", type)), type)
                )
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.starterCode(spec(new TypeSpec(Kind.ARRAY, type, List.of(type), null, null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.starterCode(spec(new TypeSpec(Kind.ARRAY, null, null, List.of(), null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(spec(type), " ", Map.of("value", "1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
            () -> generator.generate(spec(type), "int solution(int value) { return value; }", Map.of("other", "1"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /** 객체 필드·중복 키·배열 패턴을 원문 노출 없이 거절한다. */
    @Test
    @DisplayName("객체 필드 집합과 배열의 길이·순서·반복 패턴을 검사한다")
    void rejectsInvalidShapes() {
        String source = "int solution(int value) { return value; }";
        var object = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("n", scalar(Kind.INT32)));
        for (String json : List.of("{}", "{\"n\":1,\"extra\":2}", "{\"n\":1,\"n\":2}", "{\"n\":null}")) {
            assertThatThrownBy(() -> generator.generate(spec(object), source, Map.of("value", json)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        var pattern = List.of(scalar(Kind.INT32), scalar(Kind.STRING));
        for (var type : List
            .of(
                new TypeSpec(Kind.ARRAY, null, pattern, null, null),
                new TypeSpec(Kind.ARRAY, null, null, pattern, null)
            )) {
            for (String json : List.of("[1]", "[\"a\",1]", "[1,\"a\",2]")) {
                assertThatThrownBy(() -> generator.generate(spec(type), source, Map.of("value", json)))
                    .isInstanceOf(IllegalArgumentException.class);
            }
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
     * 중첩 없는 타입 명세를 만든다.
     *
     * @param kind 타입 종류
     * @return 타입 명세
     */
    private TypeSpec scalar(Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }
}

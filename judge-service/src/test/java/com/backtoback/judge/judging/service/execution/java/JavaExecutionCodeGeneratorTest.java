package com.backtoback.judge.judging.service.execution.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.FunctionSpec.Parameter;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/** 함수 명세·입력 검증과 Java 실행 코드 생성 결과를 검증한다. */
class JavaExecutionCodeGeneratorTest {
    private final JavaExecutionCodeGenerator generator = new JavaExecutionCodeGenerator();

    /**
     * Java 예약어·리터럴·단일 밑줄을 두 생성 경로에서 함께 거절한다.
     *
     * @param name 매개변수로 사용할 수 없는 이름
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "class",
            "int",
            "public",
            "return",
            "switch",
            "volatile",
            "const",
            "goto",
            "true",
            "false",
            "null",
            "_"
        }
    )
    @DisplayName("Java 예약어와 리터럴은 기본 코드와 실행 코드 생성 전에 거절한다")
    void rejectsReservedParameterNames(String name) {
        var type = scalar(Kind.INT32);
        var function = new FunctionSpec("solution", List.of(new Parameter(name, type)), type);
        assertThatThrownBy(() -> generator.starterCode(function)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(function, "class Solution {}", Map.of(name, "1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 예약어와 비슷한 이름과 매개변수 위치에서 유효한 문맥 키워드는 허용한다.
     *
     * @param name 매개변수로 사용할 수 있는 이름
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "className",
            "class_",
            "Class",
            "_value",
            "var",
            "record",
            "yield",
            "sealed",
            "permits"
        }
    )
    @DisplayName("예약어의 접두·접미 이름과 유효한 문맥 키워드는 허용한다")
    void acceptsLegalParameterNames(String name) {
        var type = scalar(Kind.INT32);
        var function = new FunctionSpec("solution", List.of(new Parameter(name, type)), type);
        assertThat(generator.starterCode(function)).contains("int " + name);
        assertThat(generator.generate(function, "class Solution {}", Map.of(name, "1")).stdin()).isNotBlank();
    }

    /** 기본 타입과 배열의 Java 선언을 확인한다. */
    @ParameterizedTest
    @CsvSource(
        {
            "INT32,int",
            "INT64,long",
            "FLOAT32,float",
            "FLOAT64,double",
            "STRING,String",
            "BOOLEAN,boolean"
        }
    )
    @DisplayName("기본 타입과 동일 타입 배열에 맞는 함수 선언을 생성한다")
    void generatesTypedStarter(Kind kind, String javaType) {
        var type = scalar(kind);
        assertThat(generator.starterCode(spec(type)))
            .contains("public " + javaType + " solution(" + javaType + " value)");
        var array = new TypeSpec(Kind.ARRAY, type, null, null, null);
        assertThat(generator.starterCode(spec(array)))
            .contains("public " + javaType + "[] solution(" + javaType + "[] value)");
    }

    /** 요청 모델·고정 실행 환경·자원 제한을 재사용한다. */
    @Test
    @DisplayName("기존 JudgeExecutionCreateRequest에 코드와 입력을 연결한다")
    void reusesExecutionRequest() {
        var code = generator.generate(spec(scalar(Kind.INT32)), "class Solution {}", Map.of("value", "7"));
        var runtime = new JudgeRuntime("java", 91, "Java (JDK 17)", "17", null);
        assertThat(code.language()).isEqualTo(JudgeLanguage.JAVA);
        var request = code.toRequest(runtime, 2000, 7000, 262144);
        assertThat(request.language()).isEqualTo("java");
        assertThat(request.fixedRuntime()).isSameAs(runtime);
        assertThat(request.sourceCode()).isEqualTo(code.sourceCode());
        assertThat(request.stdin()).isEqualTo(code.stdin());
        assertThat(request.cpuTimeMs()).isEqualTo(2000);
        assertThat(request.wallTimeMs()).isEqualTo(7000);
        assertThat(request.memoryKiB()).isEqualTo(262144);
        assertThat(code.toRequest(null, 2000, 7000, 262144).fixedRuntime()).isNull();
        assertThatThrownBy(() -> code.toRequest(new JudgeRuntime("cpp", 54, "C++", "17", null), 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 실제 입력을 소스에 포함하지 않고 입력 순서를 함수 명세에 맞춘다. */
    @Test
    @DisplayName("테스트 값과 입력 객체의 키 순서가 실행 소스에 영향을 주지 않는다")
    void separatesInputAndPreservesParameterOrder() {
        var function
            = new FunctionSpec(
                "solution",
                List.of(new Parameter("a", scalar(Kind.INT32)), new Parameter("b", scalar(Kind.STRING))),
                scalar(Kind.STRING)
            );
        var reverse = new LinkedHashMap<String, String>();
        reverse.put("b", "\"secret-test-value\"");
        reverse.put("a", "17");
        var first = generator.generate(function, "class Solution {}", reverse);
        var second = generator.generate(function, "class Solution {}", Map.of("a", "17", "b", "\"secret-test-value\""));
        assertThat(first).isEqualTo(second);
        assertThat(first.sourceCode()).doesNotContain("secret-test-value");
    }

    /** 잘못된 JSON과 정수 타입을 원문 없이 거절한다. */
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
            "NaN"
        }
    )
    @DisplayName("INT32 입력의 타입·범위·JSON 형식을 검사한다")
    void rejectsInvalidInt(String input) {
        assertThatThrownBy(
            () -> generator.generate(spec(scalar(Kind.INT32)), "class Solution {}", Map.of("value", input))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /** 64비트 정수도 중간 {@code double} 변환 없이 범위를 확인한다. */
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
            () -> generator.generate(spec(scalar(Kind.INT64)), "class Solution {}", Map.of("value", input))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /** 실수 변환 시 무한대와 영으로의 언더플로를 차단한다. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "1e400",
            "1e-4000",
            "Infinity"
        }
    )
    @DisplayName("FLOAT64로 표현할 수 없는 입력을 거절한다")
    void rejectsDoubleOverflow(String input) {
        assertThatThrownBy(
            () -> generator.generate(spec(scalar(Kind.FLOAT64)), "class Solution {}", Map.of("value", input))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /** 객체 필드와 중복 키를 엄격하게 검사한다. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "{\"a\":1,\"b\":2}",
            "{\"a\":null}",
            "{\"a\":1,\"a\":2}"
        }
    )
    @DisplayName("객체의 누락·추가·중복 필드와 null을 거절한다")
    void rejectsInvalidObject(String input) {
        var type = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("a", scalar(Kind.INT32)));
        assertThatThrownBy(() -> generator.generate(spec(type), "class Solution {}", Map.of("value", input)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 위치·반복 패턴의 길이와 타입을 검사한다. */
    @Test
    @DisplayName("배열 길이와 위치별 타입이 다르면 입력을 거절한다")
    void rejectsInvalidArrayShapes() {
        var pattern = List.of(scalar(Kind.INT32), scalar(Kind.STRING));
        for (var type : List
            .of(
                new TypeSpec(Kind.ARRAY, null, pattern, null, null),
                new TypeSpec(Kind.ARRAY, null, null, pattern, null)
            )) {
            for (String input : List.of("[1]", "[\"a\",1]", "[1,\"a\",2]")) {
                assertThatThrownBy(() -> generator.generate(spec(type), "class Solution {}", Map.of("value", input)))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    /** 코드 생성 전에 입력 이름과 함수 구조를 확인한다. */
    @Test
    @DisplayName("함수 명세와 입력 이름의 불일치를 거절한다")
    void rejectsInvalidFunction() {
        var type = scalar(Kind.INT32);
        assertThatThrownBy(() -> generator.generate(spec(type), "class Solution {}", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(spec(type), "class Solution {}", Map.of("other", "1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.starterCode(new FunctionSpec("other", List.of(), type)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
            () -> generator
                .starterCode(
                    new FunctionSpec("solution", List.of(new Parameter("a", type), new Parameter("a", type)), type)
                )
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(spec(type), " ", Map.of("value", "1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 32비트 실수의 입력 타입과 표현 범위를 검사한다.
     *
     * @param input 거부할 JSON 입력
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "3.5e38",
            "-3.5e38",
            "1e-50",
            "-1e-50",
            "NaN",
            "Infinity",
            "\"1.25\"",
            "true",
            "null"
        }
    )
    @DisplayName("FLOAT32의 범위 초과·0으로의 소실·숫자가 아닌 입력을 거절한다")
    void rejectsInvalidFloat(String input) {
        assertThatThrownBy(
            () -> generator.generate(spec(scalar(Kind.FLOAT32)), "class Solution {}", Map.of("value", input))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 입력 파싱 시 중첩 JSON도 끝까지 검사한다.
     *
     * @param input 잘못된 배열 입력
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "[-0.0",
            "[-0.0,]",
            "[-0.0] [0.0]",
            "[true]",
            "[-1e-50]"
        }
    )
    @DisplayName("음수 0 처리에서도 불완전 JSON·후행 값·타입·언더플로를 거절한다")
    void rejectsInvalidFloatArray(String input) {
        var type = new TypeSpec(Kind.ARRAY, scalar(Kind.FLOAT32), null, null, null);
        assertThatThrownBy(() -> generator.generate(spec(type), "class Solution {}", Map.of("value", input)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 단일 매개변수와 같은 반환 타입의 테스트 명세를 만든다.
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
     * @param kind 기본 타입
     * @return 타입 명세
     */
    private TypeSpec scalar(Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }
}

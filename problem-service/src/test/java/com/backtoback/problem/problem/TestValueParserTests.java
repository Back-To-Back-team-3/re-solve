package com.backtoback.problem.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.problem.global.exception.custom.ProblemTestCaseInvalidException;
import com.backtoback.problem.problem.dto.TypeSpec;
import com.backtoback.problem.problem.service.TestValueParser;

class TestValueParserTests {
    private final TestValueParser parser = new TestValueParser();

    /**
     * 정수 경계와 브라우저의 안전 정수 범위 밖에 있는 값의 정밀도를 보존하는지 검증한다.
     *
     * @param kind 선언한 정수 타입
     * @param text 파싱할 정수 JSON 텍스트
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INT32,-2147483648",
            "INT32,2147483647",
            "INT64,-9223372036854775808",
            "INT64,9223372036854775807",
            "INT64,9007199254740993"
        }
    )
    void integerBoundariesKeepExactPrecision(TypeSpec.Kind kind, String text) {
        assertThat(parser.parse(text, scalar(kind))).isEqualTo(new BigInteger(text));
    }

    /**
     * 정수 범위 초과와 정수가 아닌 JSON 토큰을 거부하는지 검증한다.
     *
     * @param kind 선언한 정수 타입
     * @param text 유효하지 않은 정수 JSON 텍스트
     */
    @ParameterizedTest
    @CsvSource(
        {
            "INT32,-2147483649",
            "INT32,2147483648",
            "INT64,-9223372036854775809",
            "INT64,9223372036854775808",
            "INT32,1.0",
            "INT64,1e0",
            "INT32,true",
            "INT64,\"1\""
        }
    )
    void invalidIntegersAreRejected(TypeSpec.Kind kind, String text) {
        assertThatThrownBy(() -> parser.parse(text, scalar(kind))).isInstanceOf(ProblemTestCaseInvalidException.class);
    }

    /**
     * 소수의 원래 숫자 값을 보존하고 실수 타입의 유효 범위를 허용하는지 검증한다.
     *
     * @param kind 선언한 실수 타입
     * @param text 파싱할 숫자 JSON 텍스트
     */
    @ParameterizedTest
    @CsvSource(
        {
            "FLOAT32,0.1",
            "FLOAT32,3.4028235e38",
            "FLOAT32,1.4e-45",
            "FLOAT32,-3.4028235e38",
            "FLOAT32,0",
            "FLOAT64,0.30000000000000004",
            "FLOAT64,1.7976931348623157e308",
            "FLOAT64,4.9e-324"
        }
    )
    void decimalValuesAreNotRoundedDuringValidation(TypeSpec.Kind kind, String text) {
        assertThat((BigDecimal)parser.parse(text, scalar(kind))).isEqualByComparingTo(new BigDecimal(text));
    }

    /**
     * 실수 변환 시 무한대나 0으로 소실되는 범위와 문자열·불리언을 거부하는지 검증한다.
     *
     * @param kind 선언한 실수 타입
     * @param text 범위를 벗어나거나 타입이 다른 JSON 텍스트
     */
    @ParameterizedTest
    @CsvSource(
        {
            "FLOAT32,3.5e38",
            "FLOAT32,1e-46",
            "FLOAT32,-3.5e38",
            "FLOAT64,1e309",
            "FLOAT64,1e-325",
            "FLOAT64,true",
            "FLOAT64,\"0.3\""
        }
    )
    void invalidDecimalsAreRejected(TypeSpec.Kind kind, String text) {
        assertThatThrownBy(() -> parser.parse(text, scalar(kind))).isInstanceOf(ProblemTestCaseInvalidException.class);
    }

    /**
     * 빈 텍스트·null·특수 숫자·잘못된 JSON·추가 토큰을 거부하는지 검증한다.
     *
     * @param text 유효하지 않은 JSON 텍스트
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            " ",
            "null",
            "NaN",
            "Infinity",
            "-Infinity",
            "1 2",
            "1 true",
            "01",
            "/*x*/1",
            "[1,]"
        }
    )
    void malformedJsonValuesAreRejected(String text) {
        assertThatThrownBy(() -> parser.parse(text, scalar(TypeSpec.Kind.INT32)))
            .isInstanceOf(ProblemTestCaseInvalidException.class);
    }

    /**
     * 문자열의 공백·줄바꿈·대소문자와 불리언 타입을 보존하며 묵시적 변환을 거부하는지 검증한다.
     */
    @Test
    void stringsAndBooleansKeepTheirOwnTypes() {
        TypeSpec string = scalar(TypeSpec.Kind.STRING);
        TypeSpec bool = scalar(TypeSpec.Kind.BOOLEAN);
        assertThat(parser.parse("\"  AbC\\n  \"", string)).isEqualTo("  AbC\n  ");
        assertThat(parser.parse("\"\"", string)).isEqualTo("");
        assertThat(parser.parse("true", bool)).isEqualTo(true);
        assertThat(parser.parse("false", bool)).isEqualTo(false);
        assertThatThrownBy(() -> parser.parse("123", string)).isInstanceOf(ProblemTestCaseInvalidException.class);
        assertThatThrownBy(() -> parser.parse("\"true\"", bool)).isInstanceOf(ProblemTestCaseInvalidException.class);
        assertThatThrownBy(() -> parser.parse("1", bool)).isInstanceOf(ProblemTestCaseInvalidException.class);
    }

    /**
     * 동일 타입·위치별·반복 패턴 배열의 길이와 순서 및 원소 타입을 검증한다.
     */
    @Test
    void arraysValidatePositionAndPatternWithoutReordering() {
        TypeSpec integer = scalar(TypeSpec.Kind.INT32);
        TypeSpec string = scalar(TypeSpec.Kind.STRING);
        TypeSpec uniform = new TypeSpec(TypeSpec.Kind.ARRAY, integer, null, null, null);
        TypeSpec positional = new TypeSpec(TypeSpec.Kind.ARRAY, null, List.of(string, integer), null, null);
        TypeSpec repeated = new TypeSpec(TypeSpec.Kind.ARRAY, null, null, List.of(integer, string, integer), null);
        assertThat(parser.parse("[3,1,2]", uniform))
            .isEqualTo(List.of(BigInteger.valueOf(3), BigInteger.ONE, BigInteger.TWO));
        assertThat(parser.parse("[\"x\",2]", positional)).isEqualTo(List.of("x", BigInteger.TWO));
        assertThat(parser.parse("[1,\"x\",2,3,\"y\",4]", repeated))
            .isEqualTo(List.of(BigInteger.ONE, "x", BigInteger.TWO, BigInteger.valueOf(3), "y", BigInteger.valueOf(4)));
        assertThat(parser.parse("[]", uniform)).isEqualTo(List.of());
        assertThat(parser.parse("[]", repeated)).isEqualTo(List.of());
        assertThat(parser.parse("[]", new TypeSpec(TypeSpec.Kind.ARRAY, null, List.of(), null, null)))
            .isEqualTo(List.of());
        for (String invalid : List.of("[\"x\",1,2]", "[1,\"x\"]", "[1,\"x\",null]", "{}")) {
            assertThatThrownBy(() -> parser.parse(invalid, repeated))
                .isInstanceOf(ProblemTestCaseInvalidException.class);
        }
        assertThatThrownBy(() -> parser.parse("[2,\"x\"]", positional))
            .isInstanceOf(ProblemTestCaseInvalidException.class);
        assertThatThrownBy(() -> parser.parse("[]", positional)).isInstanceOf(ProblemTestCaseInvalidException.class);
    }

    /**
     * 중첩 객체의 키 순서와 배열을 보존하고 필드 누락·추가·중복·null을 거부하는지 검증한다.
     */
    @Test
    void objectsRequireExactlyTheDeclaredFields() {
        TypeSpec list = new TypeSpec(TypeSpec.Kind.ARRAY, scalar(TypeSpec.Kind.INT64), null, null, null);
        TypeSpec object
            = new TypeSpec(
                TypeSpec.Kind.OBJECT,
                null,
                null,
                null,
                Map.of("values", list, "name", scalar(TypeSpec.Kind.STRING))
            );
        Object value = parser.parse("{\"name\":\"x\",\"values\":[9007199254740993]}", object);
        assertThat(value).isEqualTo(Map.of("name", "x", "values", List.of(new BigInteger("9007199254740993"))));
        assertThat(parser.parse("{\"values\":[9007199254740993],\"name\":\"x\"}", object)).isEqualTo(value);
        assertThat(parser.parse("{}", new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, Map.of())))
            .isEqualTo(Map.of());
        for (String invalid : List
            .of(
                "{}",
                "[]",
                "{\"values\":[],\"name\":null}",
                "{\"values\":[],\"name\":\"x\",\"extra\":1}",
                "{\"values\":[],\"name\":\"x\",\"name\":\"y\"}",
                "{\"values\":[null],\"name\":\"x\"}"
            )) {
            assertThatThrownBy(() -> parser.parse(invalid, object)).isInstanceOf(ProblemTestCaseInvalidException.class);
        }
    }

    /**
     * 고정 위치 배열 내부의 객체와 반복 패턴 배열을 재귀적으로 검증하는지 검증한다.
     */
    @Test
    void mixedNestedContainersKeepDeclaredTypes() {
        TypeSpec repeated
            = new TypeSpec(
                TypeSpec.Kind.ARRAY,
                null,
                null,
                List.of(scalar(TypeSpec.Kind.INT64), scalar(TypeSpec.Kind.BOOLEAN)),
                null
            );
        TypeSpec object = new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, Map.of("values", repeated));
        TypeSpec positional
            = new TypeSpec(TypeSpec.Kind.ARRAY, null, List.of(scalar(TypeSpec.Kind.STRING), object), null, null);
        Object result = parser.parse("[\"x\",{\"values\":[9007199254740993,true]}]", positional);
        assertThat(result).isEqualTo(List.of("x", Map.of("values", List.of(new BigInteger("9007199254740993"), true))));
        for (String invalid : List
            .of(
                "[\"x\",{\"values\":[true,9007199254740993]}]",
                "[\"x\",{\"values\":[9007199254740993]}]",
                "[\"x\",{\"values\":[9007199254740993,1]}]"
            )) {
            assertThatThrownBy(() -> parser.parse(invalid, positional))
                .isInstanceOf(ProblemTestCaseInvalidException.class);
        }
    }

    /**
     * 기본 타입 명세를 테스트용으로 생성한다.
     *
     * @param kind 기본 타입 종류
     * @return 하위 필드가 없는 타입 명세
     */
    private TypeSpec scalar(TypeSpec.Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }
}

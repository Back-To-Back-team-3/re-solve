package com.backtoback.judge.judging.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;

/** {@code TypeSpec}의 구조·숫자 정규화와 기대값 오류·사용자 오답의 구분을 검증한다. */
class JudgeReturnComparatorTest {
    private final JudgeReturnComparator comparator = new JudgeReturnComparator();

    /**
     * 기본 값은 선언 타입에 맞춰 비교하며 자동 타입 변환을 하지 않는다.
     *
     * @param kind 선언 타입
     * @param expected 기대 JSON
     * @param actual 실제 JSON
     * @param matched 기대한 일치 여부
     */
    @ParameterizedTest
    @CsvSource(
        value = {
            "INT32|2147483647|2147483647|true",
            "INT32|-2147483648|-2147483648|true",
            "INT32|1|1.0|false",
            "INT32|1|1e0|false",
            "INT32|1|2147483648|false",
            "INT32|1|-2147483649|false",
            "INT32|1|\"1\"|false",
            "INT32|1|true|false",
            "INT64|9223372036854775807|9223372036854775807|true",
            "INT64|-9223372036854775808|-9223372036854775808|true",
            "INT64|9007199254740993|9007199254740992|false",
            "INT64|1|9223372036854775808|false",
            "INT64|1|-9223372036854775809|false",
            "FLOAT32|16777217|16777216|true",
            "FLOAT32|16777217|16777218|false",
            "FLOAT32|0.1|0.10000000149011612|true",
            "FLOAT32|1.000000059604644775390625|1|true",
            "FLOAT32|-0.0|0.0|true",
            "FLOAT32|0|1e-100|false",
            "FLOAT32|0|1e100|false",
            "FLOAT32|1.4e-45|1.401298464324817e-45|true",
            "FLOAT32|3.4028235e38|3.4028234663852886e38|true",
            "FLOAT64|0.1|0.10000000000000001|true",
            "FLOAT64|0.1|0.10000000000000002|false",
            "FLOAT64|9007199254740993|9007199254740992|true",
            "FLOAT64|-0.0|0|true",
            "FLOAT64|5e-324|4.9406564584124654e-324|true",
            "FLOAT64|0|1e-400|false",
            "FLOAT64|0|1e400|false",
            "FLOAT64|1|\"1\"|false",
            "FLOAT64|1|false|false",
            "FLOAT64|1|NaN|false",
            "FLOAT64|1|Infinity|false",
            "STRING|\"abc\"|\"abc\"|true",
            "STRING|\"abc\"|\"ABC\"|false",
            "STRING|\"abc\"|\"abc \"|false",
            "STRING|\"a\\nb\"|\"ab\"|false",
            "STRING|\"\"|\"\"|true",
            "STRING|\"1\"|1|false",
            "BOOLEAN|true|true|true",
            "BOOLEAN|true|1|false",
            "BOOLEAN|false|\"false\"|false",
            "BOOLEAN|true|false|false"
        },
        delimiter = '|'
    )
    @DisplayName("기본 타입을 검증하고 선언 정밀도로 정규화한 값만 비교한다")
    void comparesDeclaredScalars(Kind kind, String expected, String actual, boolean matched) {
        assertThat(comparator.matches(type(kind), expected, actual)).isEqualTo(matched);
    }

    /**
     * 중간 binary64 반올림으로 사라질 수 있는 차이를 binary32 직접 변환으로 유지한다.
     */
    @Test
    @DisplayName("FLOAT32 변환은 double을 거치지 않고 중간값보다 큰 소수를 올바르게 반올림한다")
    void avoidsDoubleRounding() {
        String decimal = "1.00000005960464477539062500000000000000000000000000000000000000000000000000001";
        assertThat(comparator.matches(type(Kind.FLOAT32), "1.00000011920928955078125", decimal)).isTrue();
        assertThat(comparator.matches(type(Kind.FLOAT32), "1", decimal)).isFalse();
    }

    /**
     * 긴 숫자 표기도 선언 정밀도로 비교하며 기대값과 객체 필드에 동일한 규칙을 적용한다.
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
    @DisplayName("기본 숫자 토큰 길이를 넘는 소수도 기대값과 실제값 모두 정확히 비교한다")
    void comparesLongDecimals(Kind kind) {
        String decimal = "1." + "0".repeat(1200);
        assertThat(comparator.matches(type(kind), "1", decimal)).isTrue();
        assertThat(comparator.matches(type(kind), decimal, "1")).isTrue();
        assertThat(comparator.matches(type(kind), "2", decimal)).isFalse();
        var object = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("value", type(kind)));
        String longObject = "{\"value\":" + decimal + "}";
        assertThat(comparator.matches(object, "{\"value\":1}", longObject)).isTrue();
        assertThat(comparator.matches(object, longObject, "{\"value\":1}")).isTrue();
    }

    /**
     * 동일 원소 타입 배열은 중첩 구조와 순서를 유지한다.
     */
    @Test
    @DisplayName("동일 타입 중첩 배열은 순서·길이·원소 타입을 모두 비교한다")
    void comparesNestedArrays() {
        var nested = array(array(type(Kind.INT32)));
        assertThat(comparator.matches(nested, "[[1,2],[]]", "[[1,2],[]]")).isTrue();
        assertThat(comparator.matches(nested, "[[1,2],[]]", "[[2,1],[]]")).isFalse();
        assertThat(comparator.matches(nested, "[[1,2],[]]", "[[1,2]]")).isFalse();
        assertThat(comparator.matches(nested, "[[1,2],[]]", "[[1,\"2\"],[]]")).isFalse();
        assertThat(comparator.matches(nested, "[[1,2],[]]", "[1,2]")).isFalse();
    }

    /**
     * 혼합 배열에서 고정 위치와 반복 패턴의 제약을 적용한다.
     */
    @Test
    @DisplayName("위치별·반복 패턴 배열은 지정된 타입과 길이를 유지한다")
    void comparesMixedArrayPatterns() {
        var members = List.of(type(Kind.INT32), type(Kind.STRING));
        var positions = new TypeSpec(Kind.ARRAY, null, members, null, null);
        var repeated = new TypeSpec(Kind.ARRAY, null, null, members, null);
        assertThat(comparator.matches(positions, "[1,\"a\"]", "[1,\"a\"]")).isTrue();
        assertThat(comparator.matches(positions, "[1,\"a\"]", "[1,\"a\",1,\"a\"]")).isFalse();
        assertThat(comparator.matches(positions, "[1,\"a\"]", "[\"a\",1]")).isFalse();
        assertThat(comparator.matches(repeated, "[]", "[]")).isTrue();
        assertThat(comparator.matches(repeated, "[1,\"a\",2,\"b\"]", "[1,\"a\",2,\"b\"]")).isTrue();
        assertThat(comparator.matches(repeated, "[1,\"a\"]", "[1]")).isFalse();
        var empty = new TypeSpec(Kind.ARRAY, null, List.of(), null, null);
        assertThat(comparator.matches(empty, "[]", "[]")).isTrue();
        assertThat(comparator.matches(empty, "[]", "[1]")).isFalse();
    }

    /**
     * 객체 키 순서는 무시하되 키 집합과 필드별 타입 계약은 그대로 적용한다.
     */
    @Test
    @DisplayName("객체는 키 순서를 무시하고 추가·누락·중복 키와 중첩 필드 오류를 거절한다")
    void comparesObjects() {
        var object
            = new TypeSpec(
                Kind.OBJECT,
                null,
                null,
                null,
                Map.of("values", array(type(Kind.FLOAT32)), "flag", type(Kind.BOOLEAN))
            );
        String expected = "{\"values\":[0.1],\"flag\":true}";
        assertThat(comparator.matches(object, expected, "{\"flag\":true,\"values\":[0.10000000149011612]}")).isTrue();
        assertThat(comparator.matches(object, expected, "{\"values\":[0.1]}")).isFalse();
        assertThat(comparator.matches(object, expected, "{\"values\":[0.1],\"flag\":true,\"extra\":1}")).isFalse();
        assertThat(comparator.matches(object, expected, "{\"values\":[0.1],\"flag\":true,\"flag\":true}")).isFalse();
        assertThat(comparator.matches(object, expected, "{\"values\":[\"0.1\"],\"flag\":true}")).isFalse();
        var empty = new TypeSpec(Kind.OBJECT, null, null, null, Map.of());
        assertThat(comparator.matches(empty, "{}", "{}")).isTrue();
        assertThat(comparator.matches(empty, "{}", "[]")).isFalse();
    }

    /**
     * 정상 반환값으로 해석할 수 없는 출력은 사용자 불일치로 처리한다.
     *
     * @param actual 잘못된 실제 출력
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            " ",
            "null",
            "1 2",
            "1 trailing",
            "[1",
            "{}",
            "[1]",
            "NaN"
        }
    )
    @DisplayName("누락·잘못된 JSON·여러 JSON 값은 반환값 불일치로 처리한다")
    void rejectsInvalidActualOutput(String actual) {
        assertThat(comparator.matches(type(Kind.INT32), "1", actual)).isFalse();
    }

    /**
     * 기대값 오류를 사용자 오답과 구분하며 오류 원문을 예외에 노출하지 않는다.
     *
     * @param expected 잘못된 기대 JSON
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            "null",
            "1 2",
            "2147483648",
            "\"hidden-secret\"",
            "1.0",
            "true",
            "1e9999999999"
        }
    )
    @DisplayName("고정 회차의 기대값 오류는 사용자 WA로 바꾸지 않고 안전한 예외로 전달한다")
    void rejectsInvalidExpectedData(String expected) {
        assertThatThrownBy(() -> comparator.matches(type(Kind.INT32), expected, "1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("hidden-secret");
    }

    /**
     * 표현할 수 없는 부동소수 기대값을 사용자 오답으로 숨기지 않는다.
     *
     * @param kind 선언 부동소수 타입
     * @param expected 잘못된 기대 JSON
     */
    @ParameterizedTest
    @CsvSource(
        value = {
            "FLOAT32|1e-100",
            "FLOAT32|1e100",
            "FLOAT64|1e-400",
            "FLOAT64|1e400",
            "FLOAT64|NaN"
        },
        delimiter = '|'
    )
    @DisplayName("기대값의 부동소수 범위 초과·언더플로·비유한 값은 호출자 오류로 전달한다")
    void rejectsInvalidFloatingExpectation(Kind kind, String expected) {
        assertThatThrownBy(() -> comparator.matches(type(kind), expected, "0"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 명세 자체가 잘못된 경우 실제 반환값을 비교하지 않는다.
     */
    @Test
    @DisplayName("잘못된 타입 명세와 중복 키가 있는 기대값은 호출자 오류로 전달한다")
    void rejectsInvalidSpecifications() {
        assertThatThrownBy(() -> comparator.matches(null, "1", "1")).isInstanceOf(IllegalArgumentException.class);
        var invalid = new TypeSpec(Kind.ARRAY, type(Kind.INT32), List.of(), null, null);
        assertThatThrownBy(() -> comparator.matches(invalid, "[]", "[]")).isInstanceOf(IllegalArgumentException.class);
        var object = new TypeSpec(Kind.OBJECT, null, null, null, Map.of("field", type(Kind.INT32)));
        assertThatThrownBy(() -> comparator.matches(object, "{\"field\":1,\"field\":1}", "{\"field\":1}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 중첩 필드가 없는 기본 타입 명세를 만든다.
     *
     * @param kind 기본 타입
     * @return 기본 타입 명세
     */
    private static TypeSpec type(Kind kind) {
        return new TypeSpec(kind, null, null, null, null);
    }

    /**
     * 원소 타입이 일정한 배열 명세를 만든다.
     *
     * @param element 원소 타입
     * @return 배열 명세
     */
    private static TypeSpec array(TypeSpec element) {
        return new TypeSpec(Kind.ARRAY, element, null, null, null);
    }
}

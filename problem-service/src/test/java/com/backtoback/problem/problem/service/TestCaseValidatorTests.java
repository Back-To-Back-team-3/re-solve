package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

/** 전체 테스트의 구성·인자 순서·중복과 업무 오류 코드를 확인한다. */
class TestCaseValidatorTests {
    private final TestCaseValidator validator
        = new TestCaseValidator(new FunctionSpecValidator(), new TestValueParser());

    /**
     * 입력 객체의 키 순서와 관계없이 선언 순서로 인자를 구성하고 공개 예제도 포함하는지 검증한다.
     */
    @DisplayName("모든 테스트의 인자를 선언 순서로 정리하고 원문을 보존한다")
    @Test
    void parametersFollowDeclarationOrderAndSamplesAreIncluded() {
        // given
        TypeSpec integer = scalar(TypeSpec.Kind.INT64);
        var spec
            = new FunctionSpec(
                "solution",
                List.of(new FunctionSpec.Parameter("a", integer), new FunctionSpec.Parameter("b", integer)),
                integer
            );
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("b", "2");
        arguments.put("a", "9007199254740993");
        var sample = new TestCaseRequest(true, arguments, "9007199254740995");
        var hidden = new TestCaseRequest(false, Map.of("a", "0", "b", "1"), "1");

        // when
        var parsed = validator.validate(spec, List.of(sample, hidden));

        // then
        assertThat(parsed)
            .containsExactly(
                new ParsedTestCase(
                    List.of(new BigInteger("9007199254740993"), BigInteger.TWO),
                    new BigInteger("9007199254740995")
                ),
                new ParsedTestCase(List.of(BigInteger.ZERO, BigInteger.ONE), BigInteger.ONE)
            );
        assertThat(sample.argumentTexts()).containsEntry("a", "9007199254740993");
        assertThat(sample.expectedReturnText()).isEqualTo("9007199254740995");
        assertThatThrownBy(() -> parsed.add(parsed.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 매개변수가 없는 함수와 배열 반환값을 지원하는지 검증한다.
     */
    @DisplayName("매개변수 없는 함수와 배열 반환값을 허용한다")
    @Test
    void parameterlessFunctionsAndArrayReturnsAreSupported() {
        TypeSpec array = new TypeSpec(TypeSpec.Kind.ARRAY, scalar(TypeSpec.Kind.INT32), null, null, null);
        var spec = new FunctionSpec("solution", List.of(), array);
        var parsed
            = validator
                .validate(
                    spec,
                    List.of(new TestCaseRequest(true, Map.of(), "[1]"), new TestCaseRequest(false, Map.of(), "[]"))
                );
        assertThat(parsed.getFirst().arguments()).isEmpty();
        assertThat(parsed.getFirst().expectedReturn()).isEqualTo(List.of(BigInteger.ONE));
        assertThat(parsed.getLast().expectedReturn()).isEqualTo(List.of());
    }

    /**
     * 공개 또는 숨김 테스트가 없거나 공개 여부가 누락되면 거부하는지 검증한다.
     */
    @DisplayName("공개 예제와 숨김 테스트가 모두 있어야 한다")
    @Test
    void bothSampleAndHiddenCasesAreRequired() {
        var spec = singleParameter(scalar(TypeSpec.Kind.INT32));
        var sample = new TestCaseRequest(true, Map.of("value", "1"), "1");
        var hidden = new TestCaseRequest(false, Map.of("value", "2"), "2");
        var missingFlag = new TestCaseRequest(null, Map.of("value", "3"), "3");
        for (List<TestCaseRequest> cases : Arrays
            .asList(
                null,
                List.<TestCaseRequest>of(),
                List.of(sample),
                List.of(hidden),
                List.of(sample, missingFlag),
                Arrays.asList(sample, null)
            )) {
            assertThatThrownBy(() -> validator.validate(spec, cases))
                .isInstanceOf(ProblemException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
        }
    }

    /**
     * 입력 이름 누락·추가·null 입력·기대값 오류를 거부하는지 검증한다.
     */
    @DisplayName("입력 이름과 필수 값이 명세와 다르면 거절한다")
    @Test
    void missingExtraAndInvalidValuesAreRejected() {
        // given
        var spec = singleParameter(scalar(TypeSpec.Kind.INT32));
        var sample = new TestCaseRequest(true, Map.of("value", "1"), "1");
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("value", null);
        // when & then
        for (var invalid : List
            .of(
                new TestCaseRequest(false, Map.of(), "2"),
                new TestCaseRequest(false, Map.of("other", "2"), "2"),
                new TestCaseRequest(false, Map.of("value", "2", "extra", "3"), "2"),
                new TestCaseRequest(false, null, "2"),
                new TestCaseRequest(false, nullValue, "2"),
                new TestCaseRequest(false, Map.of("value", "null"), "2"),
                new TestCaseRequest(false, Map.of("value", "2"), null),
                new TestCaseRequest(false, Map.of("value", "2"), "\"2\"")
            )) {
            assertThatThrownBy(() -> validator.validate(spec, List.of(sample, invalid)))
                .isInstanceOf(ProblemException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
        }
    }

    /**
     * 공개 여부·JSON 공백·객체 키 순서·소수 표기만 다른 테스트도 중복으로 거부하는지 검증한다.
     */
    @DisplayName("공개 여부와 JSON 표기만 다른 같은 테스트는 중복으로 거절한다")
    @Test
    void duplicateCasesUseValuesRatherThanRawJsonOrVisibility() {
        // given
        TypeSpec object
            = new TypeSpec(
                TypeSpec.Kind.OBJECT,
                null,
                null,
                null,
                Map.of("a", scalar(TypeSpec.Kind.INT32), "b", scalar(TypeSpec.Kind.STRING))
            );
        var spec
            = new FunctionSpec(
                "solution",
                List.of(new FunctionSpec.Parameter("value", object)),
                scalar(TypeSpec.Kind.FLOAT64)
            );
        var sample = new TestCaseRequest(true, Map.of("value", "{\"a\":1,\"b\":\"x\"}"), "0.30");
        var hidden = new TestCaseRequest(false, Map.of("value", " { \"b\":\"x\", \"a\":1 } "), "0.3");
        // when & then
        assertThatThrownBy(() -> validator.validate(spec, List.of(sample, hidden)))
            .isInstanceOf(ProblemException.class)
            .hasFieldOrPropertyWithValue("errorCode", ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
        var integralReturn = new TestCaseRequest(true, sample.argumentTexts(), "1");
        var decimalReturn = new TestCaseRequest(false, hidden.argumentTexts(), "1.0");
        assertThatThrownBy(() -> validator.validate(spec, List.of(integralReturn, decimalReturn)))
            .isInstanceOf(ProblemException.class)
            .hasFieldOrPropertyWithValue("errorCode", ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
    }

    /**
     * 배열 순서나 문자열 공백이 다르면 서로 다른 테스트 값으로 유지하는지 검증한다.
     */
    @DisplayName("배열 순서와 문자열 공백이 다르면 다른 테스트로 유지한다")
    @Test
    void arrayOrderAndStringWhitespaceAreSignificant() {
        TypeSpec array = new TypeSpec(TypeSpec.Kind.ARRAY, scalar(TypeSpec.Kind.STRING), null, null, null);
        var spec = singleParameter(array);
        var cases
            = List
                .of(
                    new TestCaseRequest(true, Map.of("value", "[\"a\",\"b\"]"), "[\"a\",\"b\"]"),
                    new TestCaseRequest(false, Map.of("value", "[\"b\",\"a\"]"), "[\"a\",\"b\"]"),
                    new TestCaseRequest(false, Map.of("value", "[\" a\",\"b\"]"), "[\"a\",\"b\"]")
                );
        assertThat(validator.validate(spec, cases)).hasSize(3);
    }

    /**
     * 같은 업무 예외를 사용해도 함수 명세 오류와 테스트 오류 코드가 구분되는지 검증한다.
     */
    @DisplayName("함수 명세 오류와 테스트 오류는 같은 예외의 다른 코드로 구분한다")
    @Test
    void schemaAndTestErrorsHaveTheirOwnCodes() {
        assertThatThrownBy(() -> validator.validate(null, List.of()))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        assertThatThrownBy(() -> validator.validate(singleParameter(scalar(TypeSpec.Kind.INT32)), List.of()))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID)
            );
    }

    /**
     * 미확정된 테스트 개수·1 MiB 크기·5단계 깊이 상한을 임의로 적용하지 않는지 검증한다.
     */
    @DisplayName("테스트 개수·크기·깊이에 미정인 업무 제한을 추가하지 않는다")
    @Test
    void unconfirmedBusinessLimitsAreNotIntroduced() {
        var integers
            = IntStream
                .range(0, 101)
                .mapToObj(index -> new TestCaseRequest(index == 0, Map.of("value", Integer.toString(index)), "0"))
                .toList();
        assertThat(validator.validate(singleParameter(scalar(TypeSpec.Kind.INT32)), integers)).hasSize(101);

        String largeText = "\"" + "가".repeat(400_000) + "\"";
        var largeCases
            = List
                .of(
                    new TestCaseRequest(true, Map.of("value", largeText), "\"\""),
                    new TestCaseRequest(false, Map.of("value", "\"\""), largeText)
                );
        assertThat(validator.validate(singleParameter(scalar(TypeSpec.Kind.STRING)), largeCases)).hasSize(2);

        TypeSpec nested = scalar(TypeSpec.Kind.INT32);
        for (int depth = 0; depth < 6; depth++) {
            nested = new TypeSpec(TypeSpec.Kind.ARRAY, nested, null, null, null);
        }
        var nestedCases
            = List
                .of(
                    new TestCaseRequest(true, Map.of("value", "[[[[[[1]]]]]]"), "[[[[[[1]]]]]]"),
                    new TestCaseRequest(false, Map.of("value", "[[[[[[2]]]]]]"), "[[[[[[2]]]]]]")
                );
        assertThat(validator.validate(singleParameter(nested), nestedCases)).hasSize(2);
    }

    /**
     * 입력과 반환에 같은 타입을 사용하는 단일 매개변수 함수 명세를 생성한다.
     *
     * @param type 입력·반환 타입
     * @return value 매개변수 하나를 가진 함수 명세
     */
    private FunctionSpec singleParameter(TypeSpec type) {
        return new FunctionSpec("solution", List.of(new FunctionSpec.Parameter("value", type)), type);
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

package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;

/** 함수 구조와 재귀 타입의 공통 검증 계약을 확인한다. */
class FunctionSpecValidatorTests {
    private final FunctionSpecValidator validator = new FunctionSpecValidator();

    /**
     * 지원하는 기본 타입이 입력과 반환 타입으로 허용되는지 검증한다.
     *
     * @param kind 검증할 기본 타입
     */
    @DisplayName("지원하는 기본 타입을 입력과 반환에 사용할 수 있다")
    @ParameterizedTest
    @EnumSource(
        value = TypeSpec.Kind.class,
        names = {
            "ARRAY",
            "OBJECT"
        },
        mode = EnumSource.Mode.EXCLUDE
    )
    void scalarTypesAreSupported(TypeSpec.Kind kind) {
        TypeSpec type = scalar(kind);
        var spec = new FunctionSpec("solution", List.of(new FunctionSpec.Parameter("value", type)), type);
        assertThatCode(() -> validator.validate(spec)).doesNotThrowAnyException();
    }

    /**
     * 세 배열 형태와 중첩 객체 및 빈 배열·객체 명세를 허용하는지 검증한다.
     */
    @DisplayName("배열 세 방식과 중첩·빈 컨테이너 명세를 허용한다")
    @Test
    void nestedAndEmptyContainerSpecsAreSupported() {
        TypeSpec integer = scalar(TypeSpec.Kind.INT32);
        TypeSpec uniform = new TypeSpec(TypeSpec.Kind.ARRAY, integer, null, null, null);
        TypeSpec positional = new TypeSpec(TypeSpec.Kind.ARRAY, null, List.of(integer, uniform), null, null);
        TypeSpec repeating = new TypeSpec(TypeSpec.Kind.ARRAY, null, null, List.of(integer, positional), null);
        TypeSpec object = new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, Map.of("values", repeating));
        for (var type : List
            .of(
                object,
                new TypeSpec(TypeSpec.Kind.ARRAY, null, List.of(), null, null),
                new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, Map.of())
            )) {
            assertThatCode(() -> validator.validate(new FunctionSpec("solution", List.of(), type)))
                .doesNotThrowAnyException();
        }
    }

    /**
     * 누락·중복 선택·빈 반복 패턴·타입에 맞지 않는 하위 필드를 거부하는지 검증한다.
     */
    @DisplayName("잘못 조합한 재귀 타입 명세는 요청 오류로 거절한다")
    @Test
    void malformedTypeSpecsAreRejected() {
        // given
        TypeSpec integer = scalar(TypeSpec.Kind.INT32);
        Map<String, TypeSpec> nullFieldType = new HashMap<>();
        nullFieldType.put("value", null);
        Map<String, TypeSpec> nullFieldName = new HashMap<>();
        nullFieldName.put(null, integer);
        List<TypeSpec> invalid
            = Arrays
                .asList(
                    null,
                    scalar(null),
                    new TypeSpec(TypeSpec.Kind.ARRAY, null, null, null, null),
                    new TypeSpec(TypeSpec.Kind.ARRAY, integer, List.of(integer), null, null),
                    new TypeSpec(TypeSpec.Kind.ARRAY, null, List.of(integer), List.of(integer), null),
                    new TypeSpec(TypeSpec.Kind.ARRAY, null, null, List.of(), null),
                    new TypeSpec(TypeSpec.Kind.ARRAY, integer, null, null, Map.of()),
                    new TypeSpec(TypeSpec.Kind.ARRAY, null, Arrays.asList(integer, null), null, null),
                    new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, null),
                    new TypeSpec(TypeSpec.Kind.OBJECT, integer, null, null, Map.of()),
                    new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, nullFieldType),
                    new TypeSpec(TypeSpec.Kind.OBJECT, null, null, null, nullFieldName),
                    new TypeSpec(TypeSpec.Kind.INT32, integer, null, null, null),
                    new TypeSpec(TypeSpec.Kind.STRING, null, null, null, Map.of())
                );
        // when & then
        for (var type : invalid) {
            assertThatThrownBy(() -> validator.validate(new FunctionSpec("solution", List.of(), type)))
                .isInstanceOf(ProblemException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /**
     * 함수명·목록·중복 또는 null 매개변수가 유효하지 않으면 거부하는지 검증한다.
     */
    @DisplayName("잘못된 함수 구조와 중복 매개변수는 요청 오류로 거절한다")
    @Test
    void malformedFunctionsAreRejected() {
        TypeSpec integer = scalar(TypeSpec.Kind.INT32);
        var parameter = new FunctionSpec.Parameter("value", integer);
        for (var spec : Arrays
            .asList(
                null,
                new FunctionSpec(null, List.of(), integer),
                new FunctionSpec("solve", List.of(), integer),
                new FunctionSpec("solution", null, integer),
                new FunctionSpec("solution", List.of(parameter, parameter), integer),
                new FunctionSpec("solution", Arrays.asList(parameter, null), integer),
                new FunctionSpec("solution", List.of(new FunctionSpec.Parameter("value", null)), integer)
            )) {
            assertThatThrownBy(() -> validator.validate(spec))
                .isInstanceOf(ProblemException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /**
     * 누락되거나 이름 형식에 맞지 않는 매개변수 이름을 거부하는지 검증한다.
     *
     * @param name 유효하지 않은 매개변수 이름
     */
    @DisplayName("매개변수 이름이 없거나 형식이 잘못되면 거절한다")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            " ",
            "1value",
            "a-b",
            "a b",
            "입력"
        }
    )
    void invalidParameterNamesAreRejected(String name) {
        TypeSpec integer = scalar(TypeSpec.Kind.INT32);
        var spec = new FunctionSpec("solution", List.of(new FunctionSpec.Parameter(name, integer)), integer);
        assertThatThrownBy(() -> validator.validate(spec))
            .isInstanceOf(ProblemException.class)
            .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    /**
     * 이름 형식을 만족하면 예약어 여부와 관계없이 허용하는지 검증한다.
     *
     * @param name 형식이 유효한 매개변수 이름
     */
    @DisplayName("공통 이름 형식을 만족하면 언어 예약어도 허용한다")
    @ParameterizedTest
    @ValueSource(
        strings = {
            "value",
            "value1",
            "_value",
            "class",
            "def",
            "namespace",
            "True",
            "_"
        }
    )
    void validParameterNamesAreAcceptedRegardlessOfKeywords(String name) {
        TypeSpec integer = scalar(TypeSpec.Kind.INT32);
        var spec = new FunctionSpec("solution", List.of(new FunctionSpec.Parameter(name, integer)), integer);
        assertThatCode(() -> validator.validate(spec)).doesNotThrowAnyException();
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

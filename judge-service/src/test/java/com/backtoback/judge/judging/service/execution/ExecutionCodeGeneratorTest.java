package com.backtoback.judge.judging.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.FunctionSpec.Parameter;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
import com.backtoback.judge.judging.service.execution.java.JavaExecutionCodeGenerator;

/** 언어별 생성기를 같은 인터페이스로 호출하고 공통 결과·요청으로 연결한다. */
class ExecutionCodeGeneratorTest {
    /**
     * 구체 생성기의 타입을 몰라도 기본 코드와 실행 요청을 생성할 수 있는지 확인한다.
     *
     * @param generator 공통 인터페이스로 전달받은 언어별 생성기
     */
    @ParameterizedTest
    @MethodSource("generators")
    @DisplayName("언어별 생성기를 공통 인터페이스로 호출하면 지원 언어에 맞는 실행 결과를 반환한다")
    void generatesThroughCommonInterface(ExecutionCodeGenerator generator) {
        var type = new TypeSpec(Kind.INT32, null, null, null, null);
        var spec = new FunctionSpec("solution", List.of(new Parameter("value", type)), type);
        String starter = generator.starterCode(spec);
        assertThat(starter).isNotBlank();

        ExecutionSource source = generator.generate(spec, starter, Map.of("value", "7"));
        assertThat(source.language()).isEqualTo(generator.language());
        assertThat(source.sourceCode()).isNotBlank();
        assertThat(source.stdin()).isNotBlank();
        assertThat(source.toRequest(null, 2000, 7000, 262144).language()).isEqualTo(generator.language().getCode());
    }

    /**
     * 인터페이스를 통한 호출에서도 잘못된 함수 명세를 거절한다.
     *
     * @param generator 검사할 언어별 생성기
     */
    @ParameterizedTest
    @MethodSource("generators")
    @DisplayName("공통 인터페이스를 통한 두 생성 경로 모두 잘못된 함수 명세를 거절한다")
    void rejectsInvalidSpecification(ExecutionCodeGenerator generator) {
        var type = new TypeSpec(Kind.INT32, null, null, null, null);
        var invalid = new FunctionSpec("other", List.of(), type);
        assertThatThrownBy(() -> generator.starterCode(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(invalid, "source-code", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 실제 언어별 구현을 공통 타입으로 제공한다.
     *
     * @return 공통 계약을 검증할 언어별 생성기
     */
    private static Stream<ExecutionCodeGenerator> generators() {
        return Stream.of(new JavaExecutionCodeGenerator());
    }
}

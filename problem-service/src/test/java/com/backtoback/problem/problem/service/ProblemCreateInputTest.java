package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

/** 크기 경계·원래 요청 해시·기본 제한의 입력 계약을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ProblemCreateInputTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    @Mock
    private TestCaseValidator tests;
    @Mock
    private ProblemImageReferenceParser images;
    private ProblemCreateInput input;

    /** 실제 DTO 제약과 대체한 의미 검증기로 입력 검증을 준비한다. */
    @BeforeEach
    void setUp() {
        input = new ProblemCreateInput(VALIDATORS.getValidator(), tests, images);
    }

    /** Bean Validation 리소스를 해제한다. */
    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    /**
     * 생략한 실행 제한은 등록 시 기본값으로 확정한다.
     */
    @Test
    @DisplayName("생략한 실행 제한은 등록 시 기본값으로 확정한다")
    void defaults() {
        var result = input.validate(ProblemCreateFixtures.request());
        assertThat(result).isEqualTo(new ProblemCreateInput.Validated(List.of(), 2000, 262144, 7000, 64));
    }

    /**
     * 메모리 제한의 양 끝 경계와 명시적 null 기본값을 확인한다.
     *
     * @param memoryKb 요청 메모리 제한
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(
        ints = {
            1,
            512000
        }
    )
    @DisplayName("메모리 제한은 1부터 512000까지 허용하고 null이면 기본값을 적용한다")
    void memoryLimitAccepted(Integer memoryKb) {
        assertThat(input.validate(withMemoryLimit(memoryKb)).memory()).isEqualTo(memoryKb == null ? 262144 : memoryKb);
    }

    /**
     * 메모리 제한 범위를 벗어난 입력을 거절한다.
     *
     * @param memoryKb 요청 메모리 제한
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            0,
            -1,
            512001,
            Integer.MAX_VALUE
        }
    )
    @DisplayName("메모리 제한이 양수가 아니거나 512000을 초과하면 입력 오류로 거절한다")
    void memoryLimitRejected(int memoryKb) {
        assertThatThrownBy(() -> input.validate(withMemoryLimit(memoryKb)))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
    }

    /**
     * 지정한 메모리 제한으로 등록 입력을 생성한다.
     *
     * @param memoryKb 요청 메모리 제한
     * @return 메모리 제한을 포함한 등록 입력
     */
    private ProblemCreateRequest withMemoryLimit(Integer memoryKb) {
        var request = ProblemCreateFixtures.request();
        return new ProblemCreateRequest(
            request.scope(),
            request.title(),
            request.description(),
            request.constraints(),
            request.examples(),
            request.difficulty(),
            request.tagIds(),
            request.functionSpec(),
            new ProblemCreateRequest.Limits(null, memoryKb, null, null),
            request.testCases()
        );
    }

    /**
     * 원래 본문의 변경은 기본값 적용 결과가 같아도 다른 해시다.
     */
    @Test
    @DisplayName("원래 본문의 변경은 기본값 적용 결과가 같아도 다른 해시다")
    void hashKeepsOriginalLimits() {
        var request = ProblemCreateFixtures.request();
        var explicit
            = new ProblemCreateRequest(
                request.scope(),
                request.title(),
                request.description(),
                request.constraints(),
                request.examples(),
                request.difficulty(),
                request.tagIds(),
                request.functionSpec(),
                new ProblemCreateRequest.Limits(2000, 262144, 7000, 64),
                request.testCases()
            );
        assertThat(input.hash(request)).isEqualTo(input.hash(ProblemCreateFixtures.request()));
        assertThat(input.hash(explicit)).isNotEqualTo(input.hash(request));
    }

    /** 두 매개변수의 입력 키 순서만 달라진 재요청은 같은 해시를 만든다. */
    @Test
    @DisplayName("매개변수 값이 같고 키 삽입 순서만 다르면 멱등 해시가 같다")
    void hashIgnoresArgumentKeyOrder() {
        var arguments = new LinkedHashMap<String, String>();
        arguments.put("left", "1");
        arguments.put("right", "2");
        assertThat(input.hash(twoArgumentRequest(arguments)))
            .isEqualTo(input.hash(twoArgumentRequest(new LinkedHashMap<>(arguments.reversed()))));
    }

    /**
     * 입력 키 순서를 보존한 두 매개변수 함수의 등록 요청을 만든다.
     *
     * @param arguments 테스트 입력값
     * @return 등록 요청
     */
    private ProblemCreateRequest twoArgumentRequest(Map<String, String> arguments) {
        var request = ProblemCreateFixtures.request();
        var type = request.functionSpec().returnType();
        var spec
            = new FunctionSpec(
                "solution",
                List.of(new FunctionSpec.Parameter("left", type), new FunctionSpec.Parameter("right", type)),
                type
            );
        return new ProblemCreateRequest(
            request.scope(),
            request.title(),
            request.description(),
            request.constraints(),
            request.examples(),
            request.difficulty(),
            request.tagIds(),
            spec,
            null,
            List.of(new TestCaseRequest(true, arguments, "3"), new TestCaseRequest(false, arguments, "3"))
        );
    }

    /**
     * 테스트 한 건은 UTF-8 합계 256KiB까지 허용하고 초과는 거절한다.
     */
    @Test
    @DisplayName("테스트 한 건은 UTF-8 합계 256KiB까지 허용하고 초과는 거절한다")
    void testByteBoundary() {
        var request = ProblemCreateFixtures.request();
        for (int delta : List.of(0, 1)) {
            var tests
                = List
                    .of(
                        new TestCaseRequest(true, Map.of("value", "가".repeat(87381)), "x".repeat(1 + delta)),
                        request.testCases().get(1)
                    );
            var candidate
                = new ProblemCreateRequest(
                    request.scope(),
                    request.title(),
                    request.description(),
                    request.constraints(),
                    request.examples(),
                    request.difficulty(),
                    request.tagIds(),
                    request.functionSpec(),
                    null,
                    tests
                );
            if (delta == 0) {
                input.validate(candidate);
            } else {
                assertThatThrownBy(() -> input.validate(candidate))
                    .isInstanceOfSatisfying(
                        ProblemException.class,
                        failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(ProblemErrorCode.PROBLEM_TEST_CASE_TOO_LARGE)
                    );
            }
        }
    }

    /**
     * Content-Length 없이도 전체 본문 8MiB 경계를 적용한다.
     *
     * @throws Exception 테스트 실행 또는 결과 대기에 실패한 경우
     */
    @Test
    @DisplayName("Content-Length 없이도 전체 본문 8MiB 경계를 적용한다")
    void httpByteBoundary() throws Exception {
        var reader = new ProblemCreateBodyReader(JsonMapper.builder().build());
        byte[] json = JsonMapper.builder().build().writeValueAsBytes(ProblemCreateFixtures.request());
        var bytes = new byte[8388608];
        java.util.Arrays.fill(bytes, (byte)' ');
        System.arraycopy(json, 0, bytes, 0, json.length);
        assertThat(reader.read(new ByteArrayInputStream(bytes))).isEqualTo(ProblemCreateFixtures.request());
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(new byte[8388609])))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_REQUEST_TOO_LARGE)
            );
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(ProblemException.class);
    }

    /** 회차 합계 4MiB와 테스트 최대 50개를 별도로 검증한다. */
    @Test
    @DisplayName("개별 테스트가 작아도 회차 합계 또는 개수 제한을 초과하면 거절한다")
    void aggregateLimits() {
        var request = ProblemCreateFixtures.request();
        var test = new TestCaseRequest(true, Map.of("value", "x".repeat(262143)), "1");
        for (int count : List.of(16, 17, 51)) {
            var candidate
                = new ProblemCreateRequest(
                    request.scope(),
                    request.title(),
                    request.description(),
                    request.constraints(),
                    request.examples(),
                    request.difficulty(),
                    request.tagIds(),
                    request.functionSpec(),
                    null,
                    java.util.Collections.nCopies(count, test)
                );
            if (count == 16) {
                input.validate(candidate);
            } else {
                assertThatThrownBy(() -> input.validate(candidate))
                    .isInstanceOfSatisfying(
                        ProblemException.class,
                        failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(
                                count == 17 ? ProblemErrorCode.PROBLEM_TEST_CASE_TOO_LARGE
                                    : ProblemErrorCode.PROBLEM_TEST_CASE_INVALID
                            )
                    );
            }
        }
    }
}

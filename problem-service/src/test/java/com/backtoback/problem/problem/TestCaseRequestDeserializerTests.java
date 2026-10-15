package com.backtoback.problem.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class TestCaseRequestDeserializerTests {
    private final ApplicationContextRunner runner
        = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    /**
     * DTO 변환에서 중복 입력 키와 문자열·불리언의 묵시적 변환을 거부하는지 검증한다.
     *
     * @param json 중복 키 또는 타입이 잘못된 테스트 요청 JSON
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"isSample\":true,\"argumentTexts\":{\"a\":\"1\",\"a\":\"2\"},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":true,\"argumentTexts\":{\"a\":1},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":true,\"argumentTexts\":{},\"expectedReturnText\":1.5}",
            "{\"isSample\":\"true\",\"argumentTexts\":{},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":1,\"argumentTexts\":{},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":[],\"argumentTexts\":{},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":true,\"argumentTexts\":[],\"expectedReturnText\":\"3\"}",
            "{\"isSample\":true,\"argumentTexts\":{\"a\":true},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":true,\"argumentTexts\":{\"a\":[1]},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":true,\"argumentTexts\":{},\"expectedReturnText\":false}",
            "{\"isSample\":true,\"argumentTexts\":{},\"expectedReturnText\":{}}",
            "{\"isSample\":true,\"argumentTexts\":{},\"expectedReturnText\":\"1\",\"expectedReturnText\":\"2\"}",
            "[]",
            "123"
        }
    )
    void invalidTestRequestJsonIsRejectedBeforeBinding(String json) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue(json, TestCaseRequest.class))
                .isInstanceOf(JacksonException.class);
        });
    }

    /**
     * 원문 문자열과 타입 명세가 정상 요청에서 정확히 유지되는지 검증한다.
     *
     * @param json 정상적인 테스트 요청 JSON
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"isSample\":true,\"argumentTexts\":{\"a\":\"9007199254740993\"},\"expectedReturnText\":\"3\"}",
            "{\"isSample\":false,\"argumentTexts\":{\"a\":\"9007199254740993\"},\"expectedReturnText\":\"3\"}"
        }
    )
    void validTestRequestTextsRemainStrings(String json) {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            var request = mapper.readValue(json, TestCaseRequest.class);
            assertThat(request.argumentTexts()).containsEntry("a", "9007199254740993");
            assertThat(request.expectedReturnText()).isEqualTo("3");
            var spec = mapper.readValue("""
                {"name":"solution","parameters":[],"returnType":{"kind":"INT64"}}
                """, FunctionSpec.class);
            assertThat(spec.returnType().kind().name()).isEqualTo("INT64");
        });
    }

    /**
     * 타입 명세의 잘못된 종류와 필드 구조는 기존 DTO 변환에서 거부되는지 검증한다.
     *
     * @param json 유효하지 않은 타입 명세 JSON
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"kind\":\"UNKNOWN\"}",
            "{\"kind\":\"ARRAY\",\"positionTypes\":{}}"
        }
    )
    void malformedTypeSpecJsonIsRejected(String json) {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue(json, TypeSpec.class)).isInstanceOf(JacksonException.class);
        });
    }

    /**
     * 부모 요청의 테스트 목록에서도 항목별 중복 키 검사가 적용되는지 검증한다.
     */
    @Test
    void nestedTestCasesRejectDuplicateKeys() {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue("""
                {"testCases":[{"isSample":true,"isSample":false,"argumentTexts":{},"expectedReturnText":"1"}]}
                """, RegistrationRequest.class)).isInstanceOf(JacksonException.class);
        });
    }

    /**
     * 테스트 항목 앞뒤의 일반 DTO에는 중복 키·자동 변환 설정이 전파되지 않는지 검증한다.
     */
    @Test
    void otherDtosKeepDefaultJsonBehavior() {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            var request = mapper.readValue("""
                {
                  "before":{"name":1,"name":2,"active":"true"},
                  "testCases":[
                    {"isSample":true,"argumentTexts":{"a":"123"},"expectedReturnText":"456"},
                    {"isSample":false,"argumentTexts":{"a":"789"},"expectedReturnText":"0"}
                  ],
                  "after":{"name":3,"name":4,"active":"false"}
                }
                """, RegistrationRequest.class);
            assertThat(request.before()).isEqualTo(new OtherRequest("2", true));
            assertThat(request.after()).isEqualTo(new OtherRequest("4", false));
            assertThat(request.testCases()).hasSize(2);
            assertThat(request.testCases().get(0).argumentTexts()).containsEntry("a", "123");
            assertThat(request.testCases().get(1).isSample()).isFalse();
        });
    }

    /**
     * 누락·null 값은 뒤의 업무 검증에서 처리할 수 있도록 보존하는지 검증한다.
     *
     * @param json 필드가 누락되거나 null인 요청
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "{\"isSample\":null,\"argumentTexts\":null,\"expectedReturnText\":null}"
        }
    )
    void missingAndNullFieldsRemainNull(String json) {
        runner.run(context -> {
            var request = context.getBean(ObjectMapper.class).readValue(json, TestCaseRequest.class);
            assertThat(request).isEqualTo(new TestCaseRequest(null, null, null));
        });
    }

    /**
     * 내부 리더의 설정이 바깥 매퍼의 후속 JSON 토큰 검사까지 해제하지 않는지 검증한다.
     */
    @Test
    void outerMapperStillRejectsTrailingTokens() {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue("""
                {"isSample":true,"argumentTexts":{},"expectedReturnText":"1"} {}
                """, TestCaseRequest.class)).isInstanceOf(JacksonException.class);
        });
    }

    /**
     * 입력 원문의 공백·따옴표와 null 값을 변환 없이 유지하는지 검증한다.
     */
    @Test
    void argumentTextsPreserveRawStringsAndNull() {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            var original
                = new TestCaseRequest(true, java.util.Collections.singletonMap("a", "  [1, \"hello\"]  "), "\" hi \"");
            assertThat(mapper.readValue(mapper.writeValueAsString(original), TestCaseRequest.class))
                .isEqualTo(original);
            var nullable = mapper.readValue("""
                {"isSample":true,"argumentTexts":{"a":null},"expectedReturnText":null}
                """, TestCaseRequest.class);
            assertThat(nullable.argumentTexts()).containsEntry("a", null);
            assertThat(nullable.expectedReturnText()).isNull();
        });
    }

    record OtherRequest(String name, Boolean active) {
    }

    record RegistrationRequest(OtherRequest before, List<TestCaseRequest> testCases, OtherRequest after) {
    }
}

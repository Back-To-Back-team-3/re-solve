package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class JsonConfigurationTests {
    private final ApplicationContextRunner runner
        = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JsonConfiguration.class);

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
            "{\"isSample\":1,\"argumentTexts\":{},\"expectedReturnText\":\"3\"}"
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
     * 타입 명세 내부의 중복 키·잘못된 종류·잘못된 필드 구조가 DTO 변환에서 거부되는지 검증한다.
     *
     * @param json 유효하지 않은 타입 명세 JSON
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"kind\":\"INT32\",\"kind\":\"INT64\"}",
            "{\"kind\":\"UNKNOWN\"}",
            "{\"kind\":\"ARRAY\",\"positionTypes\":{}}",
            "{\"kind\":\"OBJECT\",\"properties\":{\"a\":{\"kind\":\"INT32\"},\"a\":{\"kind\":\"STRING\"}}}"
        }
    )
    void malformedTypeSpecJsonIsRejected(String json) {
        runner.run(context -> {
            var mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue(json, TypeSpec.class)).isInstanceOf(JacksonException.class);
        });
    }
}

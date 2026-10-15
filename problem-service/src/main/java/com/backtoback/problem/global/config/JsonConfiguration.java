package com.backtoback.problem.global.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    /**
     * 요청 JSON의 중복 객체 키와 문자열·불리언의 묵시적 타입 변환을 거부하도록 설정한다.
     *
     * @return 요청에 선언된 JSON 타입과 키를 검사하는 매퍼 설정
     */
    @Bean
    JsonMapperBuilderCustomizer strictRequestJson() {
        // Spring Boot의 기본 JSON 매퍼에 적용하므로 테스트 요청 외 다른 DTO 변환에도 영향을 준다.
        // TestValueParser가 별도로 생성한 매퍼에는 이 설정이 적용되지 않는다.
        return builder -> builder
            // 같은 객체에 동일한 키가 두 번 나오면 값이 덮어써지기 전에 거부한다.
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            // isSample에 문자열 "true"나 숫자 1을 보내도 Boolean으로 자동 변환하지 않는다.
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // 문자열 대상 변환은 별도로 차단해 argumentTexts·expectedReturnText를 문자열로만 받는다.
            .withCoercionConfig(
                LogicalType.Textual,
                coercion -> coercion
                    // 정수 123을 문자열 "123"으로 자동 변환하지 않는다.
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    // 소수 1.5를 문자열 "1.5"로 자동 변환하지 않는다.
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    // 불리언 true를 문자열 "true"로 자동 변환하지 않는다.
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            );
    }
}

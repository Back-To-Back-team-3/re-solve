package com.backtoback.judge.judging.execution.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.backtoback.judge.judging.dto.FunctionSpec;

/**
 * Solution의 public 인스턴스 메서드를 실행하는 Java 17 소스를 생성한다. (정책 및 상태 §3.1·§3.2)
 * 사용자 코드를 서버 JVM에서 실행하지 않으며 Judge0 호출·판정 비교는 수행하지 않는다.
 */
@Service
public class JavaExecutionCodeGenerator {
    private final JavaTypeMapper types = new JavaTypeMapper();
    private final JavaInputEncoder inputs = new JavaInputEncoder();
    private final String template;

    /**
     * 외부 라이브러리 없이 동작하는 실행 템플릿을 읽는다.
     *
     * @throws IllegalStateException 필수 실행 템플릿이 없는 경우
     */
    public JavaExecutionCodeGenerator() {
        try {
            template
                = new ClassPathResource("judging/java/execution.template").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Java 실행 템플릿을 읽을 수 없습니다.", exception);
        }
    }

    /**
     * 검증된 함수 명세에 맞는 사용자 기본 코드를 생성한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @return 사용자가 함수 본문을 작성할 기본 코드
     * @throws IllegalArgumentException 함수 명세가 잘못된 경우
     */
    public String starterCode(FunctionSpec spec) {
        validate(spec);
        String parameters
            = spec
                .parameters()
                .stream()
                .map(parameter -> types.sourceType(parameter.type()) + " " + parameter.name())
                .collect(Collectors.joining(", "));
        return "class Solution {\n    public " + types.sourceType(spec.returnType()) + " solution(" + parameters
            + ") {\n        throw new UnsupportedOperationException();\n    }\n}\n";
    }

    /**
     * 테스트 한 건을 실행할 코드와 stdin을 생성한다. 사용자 소스는 수정하지 않고 Main을 덧붙인다.
     * 기본 코드처럼 package 없는 비공개 Solution 클래스를 사용하며, 문법 오류는 컴파일 결과로 확인한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @param sourceCode 사용자가 작성한 Solution 클래스와 import
     * @param argumentTexts 매개변수 이름별 입력 JSON 텍스트
     * @return 기존 실행 요청 모델에 연결할 코드와 입력
     * @throws IllegalArgumentException 명세·입력이 잘못되거나 코드가 비어 있는 경우
     */
    public JavaExecutionSource generate(FunctionSpec spec, String sourceCode, Map<String, String> argumentTexts) {
        validate(spec);
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("실행할 Java 코드가 필요합니다.");
        }
        // 인자 이름·값은 생성 소스에 삽입하지 않는다. 큰 입력도 Java 상수·메서드 크기 제한에 걸리지 않는다.
        String arguments
            = spec
                .parameters()
                .stream()
                .map(parameter -> "(" + types.sourceType(parameter.type()) + ") readValue(input)")
                .collect(Collectors.joining(", "));
        String runner
            = template
                .replace("${RETURN_TYPE}", types.sourceType(spec.returnType()))
                .replace("${ARGUMENTS}", arguments);
        return new JavaExecutionSource(sourceCode + "\n" + runner, inputs.encode(spec, argumentTexts));
    }

    /**
     * 코드 생성 전에 함수 구조와 중첩 타입의 지원 여부를 확인한다. 예약어 검사는 컴파일러에 맡긴다.
     *
     * @param spec 함수 명세
     * @throws IllegalArgumentException 함수명·매개변수 구조가 잘못된 경우
     */
    private void validate(FunctionSpec spec) {
        if (spec == null || !"solution".equals(spec.name()) || spec.parameters() == null) {
            throw new IllegalArgumentException("solution 함수 명세가 필요합니다.");
        }
        var names = new HashSet<String>();
        for (var parameter : spec.parameters()) {
            if (parameter == null || parameter.name() == null || !parameter.name().matches("[A-Za-z_][A-Za-z0-9_]*")
                || !names.add(parameter.name())) {
                throw new IllegalArgumentException("매개변수 이름이 올바르지 않습니다.");
            }
            types.sourceType(parameter.type());
        }
        types.sourceType(spec.returnType());
    }
}

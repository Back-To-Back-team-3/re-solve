package com.backtoback.judge.judging.execution.cpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.execution.FunctionSpecValidator;
import com.backtoback.judge.judging.execution.TypedExecutionInputEncoder;
import com.backtoback.judge.judging.service.CppExecutionSource;

/** 전역 solution 함수를 호출할 C++17 소스와 실행 입력을 생성한다. */
@Service
public class CppExecutionCodeGenerator {
    private static final Set<String> RESERVED_NAMES
        = Set
            .of(
                "alignas",
                "alignof",
                "and",
                "and_eq",
                "asm",
                "auto",
                "bitand",
                "bitor",
                "bool",
                "break",
                "case",
                "catch",
                "char",
                "char16_t",
                "char32_t",
                "class",
                "compl",
                "const",
                "constexpr",
                "const_cast",
                "continue",
                "decltype",
                "default",
                "delete",
                "do",
                "double",
                "dynamic_cast",
                "else",
                "enum",
                "explicit",
                "export",
                "extern",
                "false",
                "float",
                "for",
                "friend",
                "goto",
                "if",
                "inline",
                "int",
                "long",
                "mutable",
                "namespace",
                "new",
                "noexcept",
                "not",
                "not_eq",
                "nullptr",
                "operator",
                "or",
                "or_eq",
                "private",
                "protected",
                "public",
                "register",
                "reinterpret_cast",
                "return",
                "short",
                "signed",
                "sizeof",
                "static",
                "static_assert",
                "static_cast",
                "struct",
                "switch",
                "template",
                "this",
                "thread_local",
                "throw",
                "true",
                "try",
                "typedef",
                "typeid",
                "typename",
                "union",
                "unsigned",
                "using",
                "virtual",
                "void",
                "volatile",
                "wchar_t",
                "while",
                "xor",
                "xor_eq"
            );
    private final FunctionSpecValidator validator = new FunctionSpecValidator();
    private final CppTypeMapper types = new CppTypeMapper();
    private final TypedExecutionInputEncoder inputs = new TypedExecutionInputEncoder();
    private final String template;

    /**
     * 외부 JSON 라이브러리가 필요 없는 C++ 실행 템플릿을 읽는다.
     *
     * @throws IllegalStateException 필수 템플릿을 읽을 수 없는 경우
     */
    public CppExecutionCodeGenerator() {
        try {
            template
                = new ClassPathResource("judging/cpp/execution.template").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("C++ 실행 템플릿을 읽을 수 없습니다.", exception);
        }
    }

    /**
     * 표준 타입과 혼합 값 지원 선언을 포함한 전역 함수의 기본 코드를 생성한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @return 사용자가 함수 본문을 작성할 코드
     * @throws IllegalArgumentException 명세 또는 C++ 매개변수 이름이 잘못된 경우
     */
    public String starterCode(FunctionSpec spec) {
        validate(spec);
        String parameters
            = spec
                .parameters()
                .stream()
                .map(parameter -> types.sourceType(parameter.type()) + " " + parameter.name())
                .collect(Collectors.joining(", "));
        // 제출 코드 자체에서 표준 타입과 Value를 사용할 수 있도록 공통 선언을 제공한다.
        return template.substring(0, template.indexOf("// EXECUTION_RUNTIME")) + types.sourceType(spec.returnType())
            + " solution(" + parameters + ") {\n" + "    throw std::logic_error(\"Not implemented\");\n}\n";
    }

    /**
     * 전역 함수를 호출하고 반환값을 JSON으로 직렬화할 실행 코드를 생성한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @param sourceCode 전역 solution 함수를 포함한 사용자 코드
     * @param argumentTexts 매개변수 이름별 입력 JSON 텍스트
     * @return 실행 코드와 입력
     * @throws IllegalArgumentException 명세·입력 또는 사용자 코드가 잘못된 경우
     */
    public CppExecutionSource generate(FunctionSpec spec, String sourceCode, Map<String, String> argumentTexts) {
        validate(spec);
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("실행할 C++ 코드가 필요합니다.");
        }
        String declarations
            = IntStream
                .range(0, spec.parameters().size())
                .mapToObj(
                    index -> "auto argument" + index + " = judge_runner::Converter<"
                        + types.sourceType(spec.parameters().get(index).type()) + ">::read(input.readValue());"
                )
                .collect(Collectors.joining("\n        "));
        String arguments
            = IntStream
                .range(0, spec.parameters().size())
                .mapToObj(index -> "argument" + index)
                .collect(Collectors.joining(", "));
        String signature
            = types.sourceType(spec.returnType()) + "("
                + spec
                    .parameters()
                    .stream()
                    .map(parameter -> types.sourceType(parameter.type()))
                    .collect(Collectors.joining(", "))
                + ")";
        String runner
            = template
                .replace("${DECLARATIONS}", declarations)
                .replace("${ARGUMENTS}", arguments)
                .replace("${SIGNATURE}", signature);
        // 기본 코드가 포함한 공통 선언은 헤더 가드로 보호한다. 사용자 소스는 마지막에 삽입한다.
        return new CppExecutionSource(runner.replace("${USER_SOURCE}", sourceCode), inputs.encode(spec, argumentTexts));
    }

    /**
     * 공통 함수 명세와 C++17 예약어·구현용 예약 식별자를 검사한다.
     *
     * @param spec 검사할 명세
     * @throws IllegalArgumentException 명세 또는 이름이 잘못된 경우
     */
    private void validate(FunctionSpec spec) {
        validator.validate(spec);
        if (spec
            .parameters()
            .stream()
            .anyMatch(
                parameter -> RESERVED_NAMES.contains(parameter.name()) || parameter.name().contains("__")
                    || parameter.name().matches("_[A-Z].*")
            )) {
            throw new IllegalArgumentException("C++ 매개변수 이름이 올바르지 않습니다.");
        }
    }
}

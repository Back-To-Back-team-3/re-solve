package com.backtoback.judge.judging.service.execution.python;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.FunctionSpecValidator;
import com.backtoback.judge.judging.service.execution.TypedExecutionInputEncoder;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;

/** 최상위 동기 {@code solution} 함수를 호출할 Python 3 코드와 실행 입력을 생성한다. */
@Service
public class PythonExecutionCodeGenerator implements ExecutionCodeGenerator {
    private static final Set<String> RESERVED_NAMES
        = Set
            .of(
                "False",
                "None",
                "True",
                "and",
                "as",
                "assert",
                "async",
                "await",
                "break",
                "class",
                "continue",
                "def",
                "del",
                "elif",
                "else",
                "except",
                "finally",
                "for",
                "from",
                "global",
                "if",
                "import",
                "in",
                "is",
                "lambda",
                "nonlocal",
                "not",
                "or",
                "pass",
                "raise",
                "return",
                "try",
                "while",
                "with",
                "yield"
            );
    private final FunctionSpecValidator validator = new FunctionSpecValidator();
    private final PythonTypeMapper types = new PythonTypeMapper();
    private final TypedExecutionInputEncoder inputs = new TypedExecutionInputEncoder();
    private final String template;

    /**
     * Python 표준 라이브러리만 사용하는 실행 템플릿을 읽는다.
     *
     * @throws IllegalStateException 필수 템플릿을 읽을 수 없는 경우
     */
    public PythonExecutionCodeGenerator() {
        try {
            template
                = new ClassPathResource("judging/python/execution.template").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Python 실행 템플릿을 읽을 수 없습니다.", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public JudgeLanguage language() {
        return JudgeLanguage.PYTHON3;
    }

    /**
     * 함수 선언 순서와 표준 타입 힌트를 포함한 기본 코드를 생성한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @return 사용자가 함수 본문을 작성할 기본 코드
     * @throws IllegalArgumentException 명세 또는 Python 매개변수 이름이 잘못된 경우
     */
    @Override
    public String starterCode(FunctionSpec spec) {
        validate(spec);
        String parameters
            = spec
                .parameters()
                .stream()
                .map(parameter -> parameter.name() + ": " + types.sourceType(parameter.type()))
                .collect(Collectors.joining(", "));
        return "def solution(" + parameters + ") -> " + types.sourceType(spec.returnType())
            + ":\n    raise NotImplementedError()\n";
    }

    /**
     * 사용자 함수를 독립 이름 공간에서 실행하고 JSON 반환값과 디버그 출력을 분리할 코드를 생성한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @param sourceCode 최상위 동기 {@code solution} 함수를 포함한 사용자 코드
     * @param argumentTexts 매개변수 이름별 입력 JSON 텍스트
     * @return 실행 코드와 입력
     * @throws IllegalArgumentException 명세·입력 또는 사용자 코드가 잘못된 경우
     */
    @Override
    public ExecutionSource generate(FunctionSpec spec, String sourceCode, Map<String, String> argumentTexts) {
        validate(spec);
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("실행할 Python 코드가 필요합니다.");
        }
        String encodedSource = Base64.getEncoder().encodeToString(sourceCode.getBytes(StandardCharsets.UTF_8));
        String runner
            = template
                .replace("${USER_SOURCE}", encodedSource)
                .replace("${PARAMETER_COUNT}", Integer.toString(spec.parameters().size()));
        return new ExecutionSource(language(), runner, inputs.encode(spec, argumentTexts));
    }

    /**
     * 공통 명세와 Python 예약어를 검사한다. 사용자 코드의 구문은 실행 환경에서 확인한다.
     *
     * @param spec 검사할 함수 명세
     * @throws IllegalArgumentException 명세나 매개변수 이름이 잘못된 경우
     */
    private void validate(FunctionSpec spec) {
        validator.validate(spec);
        if (spec.parameters().stream().anyMatch(parameter -> RESERVED_NAMES.contains(parameter.name()))) {
            throw new IllegalArgumentException("Python 매개변수 이름이 올바르지 않습니다.");
        }
    }
}

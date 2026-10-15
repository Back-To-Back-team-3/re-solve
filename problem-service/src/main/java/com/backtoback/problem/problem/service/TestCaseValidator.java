package com.backtoback.problem.problem.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.backtoback.problem.global.exception.custom.CommonInvalidRequestException;
import com.backtoback.problem.global.exception.custom.ProblemTestCaseInvalidException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.ParsedTestCase;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TestCaseValidator {
    private final FunctionSpecValidator functionSpecValidator;
    private final TestValueParser valueParser;

    /**
     * 함수 명세와 공개·숨김 테스트를 검증하고 모든 테스트를 선언 순서의 실행 인자로 변환한다.
     * 요청의 JSON 원문은 변경하지 않으며 공개 예제도 결과 목록에 포함한다.
     *
     * @param spec 호출할 함수의 명세
     * @param cases 등록할 전체 테스트
     * @return 요청 테스트 순서대로 변환한 입력·기대값 목록
     * @throws CommonInvalidRequestException 함수 명세가 유효하지 않은 경우
     * @throws ProblemTestCaseInvalidException 입력·기대값·테스트 구성 또는 중복 여부가 유효하지 않은 경우
     */
    public List<ParsedTestCase> validate(FunctionSpec spec, List<TestCaseRequest> cases) {
        // 실제 값을 해석하기 전에 기준이 되는 함수 명세부터 검사한다.
        functionSpecValidator.validate(spec);
        // 테스트 목록이 누락됐거나 비어 있으면 검증을 진행할 수 없다.
        if (cases == null || cases.isEmpty()) {
            throw new ProblemTestCaseInvalidException();
        }
        // 입력에 매개변수 이름이 빠지거나 추가됐는지 비교하기 위한 집합이다. 이름의 순서는 비교하지 않는다.
        Set<String> names = new HashSet<>();
        spec.parameters().forEach(parameter -> names.add(parameter.name()));
        // 집합은 중복 검사에, 목록은 요청 순서대로 변환 결과를 반환하는 데 사용한다.
        Set<ParsedTestCase> unique = new HashSet<>();
        List<ParsedTestCase> result = new ArrayList<>(cases.size());
        boolean hasSample = false;
        boolean hasHidden = false;
        for (var testCase : cases) {
            // 공개 여부와 입력이 있어야 하며, 입력 이름은 함수에 선언된 이름과 정확히 일치해야 한다.
            if (testCase == null || testCase.isSample() == null || testCase.argumentTexts() == null
                || !testCase.argumentTexts().keySet().equals(names)) {
                throw new ProblemTestCaseInvalidException();
            }
            // 요청 객체의 키 순서와 무관하게 함수의 매개변수 선언 순서로 값을 검증·변환한다.
            List<Object> arguments = new ArrayList<>();
            for (var parameter : spec.parameters()) {
                arguments.add(valueParser.parse(testCase.argumentTexts().get(parameter.name()), parameter.type()));
            }
            // 기대값도 반환 타입으로 검증·변환하고, 입력 목록은 이후 변경할 수 없도록 복사한다.
            var parsed
                = new ParsedTestCase(
                    List.copyOf(arguments),
                    valueParser.parse(testCase.expectedReturnText(), spec.returnType())
                );
            // 공개 여부·JSON 공백·객체 키 순서가 달라도 입력과 기대값이 같으면 중복이다.
            if (!unique.add(parsed)) {
                throw new ProblemTestCaseInvalidException();
            }
            result.add(parsed);
            // 공개·숨김 테스트를 한 번이라도 발견하면 해당 플래그를 true로 유지한다.
            hasSample |= testCase.isSample();
            hasHidden |= !testCase.isSample();
        }
        // 전체 테스트에는 공개 예제와 숨김 테스트가 각각 하나 이상 있어야 한다.
        if (!hasSample || !hasHidden) {
            throw new ProblemTestCaseInvalidException();
        }
        // 공개 예제도 포함한 전체 결과를 요청 순서대로 반환하며, 결과 목록의 변경을 막는다.
        return List.copyOf(result);
    }
}

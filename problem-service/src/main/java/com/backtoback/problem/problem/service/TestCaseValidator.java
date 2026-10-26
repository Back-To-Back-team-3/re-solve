package com.backtoback.problem.problem.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import lombok.RequiredArgsConstructor;

/**
 * 공개·숨김 구성과 입력·기대값을 검증해 중복 없는 전체 테스트를 반환한다. (API 명세서 2.3.0)
 */
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
     * @throws ProblemException 함수 명세 오류는 {@code COMMON_INVALID_REQUEST}, 테스트 오류는 {@code PROBLEM_TEST_CASE_INVALID}
     */
    public List<ParsedTestCase> validate(FunctionSpec spec, List<TestCaseRequest> cases) {
        // 실제 값을 해석하기 전에 기준이 되는 함수 명세부터 검사한다.
        functionSpecValidator.validate(spec);
        if (cases == null || cases.isEmpty()) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
        }
        Set<String> names = new HashSet<>();
        spec.parameters().forEach(parameter -> names.add(parameter.name()));
        Set<ParsedTestCase> unique = new HashSet<>();
        List<ParsedTestCase> result = new ArrayList<>(cases.size());
        boolean hasSample = false;
        boolean hasHidden = false;
        for (var testCase : cases) {
            if (testCase == null || testCase.isSample() == null || testCase.argumentTexts() == null
                || !testCase.argumentTexts().keySet().equals(names)) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
            }
            // 요청 객체의 키 순서와 무관하게 함수의 매개변수 선언 순서로 값을 검증·변환한다.
            List<Object> arguments = new ArrayList<>();
            for (var parameter : spec.parameters()) {
                arguments.add(valueParser.parse(testCase.argumentTexts().get(parameter.name()), parameter.type()));
            }
            var parsed
                = new ParsedTestCase(
                    List.copyOf(arguments),
                    valueParser.parse(testCase.expectedReturnText(), spec.returnType())
                );
            // 공개 여부·JSON 공백·객체 키 순서가 달라도 입력과 기대값이 같으면 중복이다.
            if (!unique.add(parsed)) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
            }
            result.add(parsed);
            hasSample |= testCase.isSample();
            hasHidden |= !testCase.isSample();
        }
        // 공개 예제도 채점 대상이며 숨김 테스트와 함께 필요하다. (API 명세서 2.3.0)
        if (!hasSample || !hasHidden) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
        }
        return List.copyOf(result);
    }
}

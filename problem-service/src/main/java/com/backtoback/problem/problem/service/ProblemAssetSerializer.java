package com.backtoback.problem.problem.service;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 본문과 테스트의 문자열을 불변 파일용 JSON으로 직렬화하고 바이트 기준 메타 정보를 만든다.
 * 기준 문서: API 명세서 v1.1 / §2.3.0 문제·테스트 공통 데이터 형식: 원문·체크섬·크기.
 */
@Component
public class ProblemAssetSerializer {
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 본문의 세 영역을 JSON 파일로 묶는다.
     *
     * @param description 문제 설명
     * @param constraints 제한 사항
     * @param examples 예시와 해설
     * @return 저장할 JSON 바이트와 메타 정보
     * @throws NullPointerException 필드가 null인 경우
     */
    public ProblemAssetContent serializeBody(String description, String constraints, String examples) {
        var body
            = new BodyContent(
                Objects.requireNonNull(description),
                Objects.requireNonNull(constraints),
                Objects.requireNonNull(examples)
            );
        return ProblemAssetContent.fromBytes(mapper.writeValueAsBytes(body));
    }

    /**
     * 공개 여부를 제외하고 테스트 입력과 기대값의 문자열을 보존한다.
     *
     * @param testCase 저장할 테스트 입력
     * @return 저장할 JSON 바이트와 메타 정보
     * @throws NullPointerException 테스트, 입력 맵, 입력값 또는 기대값이 null인 경우
     */
    public ProblemAssetContent serializeTest(TestCaseRequest testCase) {
        Objects.requireNonNull(testCase);
        // 바깥 입력 객체의 키 순서가 달라도 같은 문자열은 같은 파일 바이트가 되도록 한다(API 명세서 2.3.0).
        var arguments = new TreeMap<>(Objects.requireNonNull(testCase.argumentTexts()));
        arguments.forEach((key, value) -> Objects.requireNonNull(value));
        var test = new TestContent(arguments, Objects.requireNonNull(testCase.expectedReturnText()));
        return ProblemAssetContent.fromBytes(mapper.writeValueAsBytes(test));
    }

    /** 본문 세 영역의 저장 표현. */
    private record BodyContent(String description, String constraints, String examples) {
    }

    /** 테스트 입력과 기대값의 저장 표현. */
    private record TestContent(Map<String, String> argumentTexts, String expectedReturnText) {
    }
}

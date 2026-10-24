package com.backtoback.judge.judging.service.execution;

import java.util.Map;

import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;

/**
 * 언어별 기본 코드와 테스트 한 건의 실행 코드를 생성하는 계약이다.
 * 사용자 코드를 실행하거나 기대값과 비교하지 않으며 내부 입력 형식은 각 구현이 결정한다.
 */
public interface ExecutionCodeGenerator {
    /**
     * 생성기가 지원하는 고정 실행 언어를 반환한다.
     *
     * @return 생성 결과의 언어와 일치하는 지원 언어
     */
    JudgeLanguage language();

    /**
     * 고정 회차의 함수 명세에 맞는 언어별 기본 코드를 생성한다.
     *
     * @param spec 함수 이름·선언 순서의 매개변수·반환 타입
     * @return 사용자가 함수 본문을 작성할 기본 코드
     * @throws IllegalArgumentException 함수 구조·타입 또는 언어별 매개변수 이름이 잘못된 경우
     */
    String starterCode(FunctionSpec spec);

    /**
     * 테스트 입력을 검증하고 지원 언어의 실행 코드와 입력을 생성한다.
     * 반환 결과의 언어는 {@link #language()}와 같아야 한다.
     *
     * @param spec 고정 회차의 함수 명세
     * @param sourceCode 해당 언어의 사용자 함수 코드
     * @param argumentTexts 매개변수 이름별 입력 JSON 텍스트
     * @return 실행 언어·템플릿을 포함한 전체 코드·검증한 입력
     * @throws IllegalArgumentException 함수 명세·입력 또는 사용자 코드가 잘못된 경우
     */
    ExecutionSource generate(FunctionSpec spec, String sourceCode, Map<String, String> argumentTexts);
}

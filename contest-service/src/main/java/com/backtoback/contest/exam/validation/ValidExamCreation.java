package com.backtoback.contest.exam.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 단일 필드 제약으로 표현하지 못하는 시험 생성의 교차 조건을 검증한다.
 * 오류는 관련 필드에 연결해 공통 검증 응답에서 위치를 찾을 수 있도록 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExamCreationValidator.class)
// Eclipse의 annotation 선언 공백 설정과 Checkstyle 충돌을 이 선언 한 줄에 한정해 조정한다.
// @formatter:off
public @interface ValidExamCreation {
    // @formatter:on
    /**
     * 교차 검증의 기본 안내를 제공한다.
     *
     * @return 기본 오류 메시지
     */
    String message() default "시험 생성 조건을 확인해 주세요.";

    /**
     * Bean Validation에서 적용할 검증 그룹을 지정한다.
     *
     * @return 기본 그룹을 사용하는 빈 배열
     */
    Class<?>[] groups() default {};

    /**
     * Bean Validation의 추가 메타데이터 타입을 지정한다.
     *
     * @return 기본 payload를 사용하는 빈 배열
     */
    Class<? extends Payload>[] payload() default {};
}

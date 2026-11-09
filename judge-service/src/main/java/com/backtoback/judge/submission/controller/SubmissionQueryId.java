package com.backtoback.judge.submission.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;

/** 내부 제출 조회의 문자열 식별자를 양수 BIGINT 범위로 제한한다. */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@Pattern(regexp = "[1-9][0-9]{0,18}")
@DecimalMax("9223372036854775807")
// 어노테이션 선언의 중괄호 공백에 대한 포맷터·Checkstyle 규칙 충돌을 피한다.
// @formatter:off
public @interface SubmissionQueryId {
    // @formatter:on
    /**
     * 식별자 검증 실패 메시지를 지정한다.
     *
     * @return 기본 한국어 오류 메시지 또는 호출 지점에서 지정한 메시지
     */
    String message() default "식별자는 양수 BIGINT 범위의 십진수여야 합니다.";

    /**
     * 제약을 적용할 검증 그룹을 지정한다.
     *
     * @return 검증 그룹
     */
    Class<?>[] groups() default {};

    /**
     * 제약에 첨부할 검증 메타데이터를 지정한다.
     *
     * @return 검증 페이로드
     */
    Class<? extends Payload>[] payload() default {};
}

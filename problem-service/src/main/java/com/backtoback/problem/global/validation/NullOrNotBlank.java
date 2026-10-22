package com.backtoback.problem.global.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * {@code null}은 허용하고 빈 문자열이나 공백으로만 이루어진 문자열은 거부한다.
 * 문자열 자체는 변경하지 않는다.
 */
@Documented
@Constraint(validatedBy = NullOrNotBlankValidator.class)
@Target(
    {
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE,
        ElementType.TYPE_USE
    }
)
@Retention(RetentionPolicy.RUNTIME)
// 어노테이션 선언의 중괄호 공백에 대한 포맷터·Checkstyle 규칙 충돌을 피한다.
// @formatter:off
public @interface NullOrNotBlank {
    // @formatter:on
    /**
     * 검증 실패 메시지를 반환한다.
     *
     * @return 공백 문자열에 대한 오류 메시지
     */
    String message() default "공백만 입력할 수 없습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

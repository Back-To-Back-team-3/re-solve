package com.backtoback.problem.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 선택적 문자열에서 {@code null}은 허용하고 빈 문자열과 공백만 있는 값은 거부한다.
 * 입력 문자열은 변경하지 않는다.
 */
public class NullOrNotBlankValidator implements ConstraintValidator<NullOrNotBlank, String> {
    /** {@inheritDoc} */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || !value.isBlank();
    }
}

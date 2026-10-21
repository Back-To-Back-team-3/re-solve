package com.backtoback.problem.tag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.problem.global.validation.NullOrNotBlank;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class TagUpdateRequestTests {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    /**
     * 검증 팩토리의 자원을 정리한다.
     */
    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    /**
     * 빈 문자열과 공백뿐인 이름을 DTO에서 거부하는지 검증한다.
     *
     * @param name 거부할 이름
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            " ",
            "   ",
            "\t\n",
            "\u2003"
        }
    )
    @DisplayName("빈 이름과 공백 이름은 NullOrNotBlank 제약을 위반한다")
    void rejectsBlankName(String name) {
        var violations = VALIDATOR.validate(new TagUpdateRequest(name, 0));
        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("name");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NullOrNotBlank.class);
        });
    }

    /**
     * 이름 유지 요청과 유효한 이름을 허용하며 검증 과정에서 원문을 바꾸지 않는지 검증한다.
     *
     * @param name 유지하거나 수정할 이름
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
            "해시",
            "  해시  ",
            "\u2003해시\u2003"
        }
    )
    @DisplayName("null과 유효한 이름은 허용하고 원문을 유지한다")
    void acceptsNullOrNonBlankName(String name) {
        var request = new TagUpdateRequest(name, 0);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.name()).isEqualTo(name);
    }

    /**
     * 기존 이름 길이 상한을 유지하는지 검증한다.
     *
     * @param length 검사할 이름 길이
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            200,
            201
        }
    )
    @DisplayName("이름은 200자까지 허용한다")
    void preservesNameLengthLimit(int length) {
        var violations = VALIDATOR.validate(new TagUpdateRequest("가".repeat(length), null));
        assertThat(violations).hasSize(length == 200 ? 0 : 1);
    }
}

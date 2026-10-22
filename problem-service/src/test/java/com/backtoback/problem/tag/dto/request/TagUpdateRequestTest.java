package com.backtoback.problem.tag.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.problem.global.validation.NullOrNotBlank;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** 태그 수정 요청 이름의 공백·널·길이 검증을 확인한다. */
class TagUpdateRequestTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

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
    @DisplayName("빈 이름과 공백 이름은 제약을 위반한다")
    void rejectsBlankName(String name) {
        var violations = VALIDATOR.validate(new TagUpdateRequest(name, 0));
        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("name");
            assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(NullOrNotBlank.class);
            assertThat(violation.getMessage()).isEqualTo("공백만 입력할 수 없습니다.");
        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
            "해시",
            "  해시  ",
            "\u2003해시\u2003"
        }
    )
    @DisplayName("null과 유효한 이름을 허용하고 원문을 유지한다")
    void acceptsNullOrNonBlankName(String name) {
        var request = new TagUpdateRequest(name, 0);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(
        ints = {
            200,
            201
        }
    )
    @DisplayName("이름 길이 제한을 200자로 유지한다")
    void preservesNameLengthLimit(int length) {
        var violations = VALIDATOR.validate(new TagUpdateRequest("가".repeat(length), null));
        if (length == 200) {
            assertThat(violations).isEmpty();
        } else {
            assertThat(violations)
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage()).isEqualTo("태그 이름은 1자 이상 200자 이하로 입력해 주세요."));
        }
    }
}

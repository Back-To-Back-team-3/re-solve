package com.backtoback.problem.tag.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** 태그 생성 요청의 필수값과 이름 길이 검증을 확인한다. */
class TagCreateRequestTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    @DisplayName("빈 태그 이름은 한국어 필드 오류로 거부한다")
    void rejectsBlankNameWithKoreanMessage() {
        var violation = VALIDATOR.validate(new TagCreateRequest(" ", 0)).iterator().next();

        assertThat(violation.getPropertyPath().toString()).isEqualTo("name");
        assertThat(violation.getMessage()).isEqualTo("태그 이름을 입력해 주세요.");
    }

    /** 유니코드 공백만 입력한 이름도 필수값 검증에서 거부되는지 확인한다. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "\u2003",
            "\u3000"
        }
    )
    @DisplayName("유니코드 공백만 있는 태그 이름은 한국어 필드 오류로 거부한다")
    void rejectsUnicodeBlankNameWithKoreanMessage(String name) {
        // given
        var request = new TagCreateRequest(name, 0);

        // when
        var violations = VALIDATOR.validate(request);

        // then
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("name");
            assertThat(violation.getMessage()).isEqualTo("태그 이름을 입력해 주세요.");
        });
    }

    @Test
    @DisplayName("태그 이름은 200자까지 허용하고 초과하면 한국어 필드 오류를 반환한다")
    void enforcesMaximumNameLengthWithKoreanMessage() {
        assertThat(VALIDATOR.validate(new TagCreateRequest("가".repeat(200), 0))).isEmpty();
        var violation = VALIDATOR.validate(new TagCreateRequest("가".repeat(201), 0)).iterator().next();

        assertThat(violation.getPropertyPath().toString()).isEqualTo("name");
        assertThat(violation.getMessage()).isEqualTo("태그 이름은 200자 이하로 입력해 주세요.");
    }

    @Test
    @DisplayName("누락한 표시 순번은 한국어 필드 오류로 거부한다")
    void rejectsMissingDisplayOrderWithKoreanMessage() {
        var violation = VALIDATOR.validate(new TagCreateRequest("해시", null)).iterator().next();

        assertThat(violation.getPropertyPath().toString()).isEqualTo("displayOrder");
        assertThat(violation.getMessage()).isEqualTo("표시 순서를 입력해 주세요.");
    }
}

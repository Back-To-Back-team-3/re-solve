package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

class ProblemImageReferenceParserTest {
    private final ProblemImageReferenceParser parser = new ProblemImageReferenceParser();

    @Test
    @DisplayName("여러 본문의 이미지 참조를 중복 없이 ID 순서로 반환한다")
    void extractsDistinctImagesInIdOrder() {
        assertThat(parser.parse("![둘](image:2)", "![하나](image:1)", "![다시](image:2)")).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("코드 영역과 이스케이프된 문법은 무시하고 이스케이프된 설명의 이미지는 인식한다")
    void respectsMarkdownCodeAndEscapes() {
        String markdown
            = "`![코드](image:999)`\n\n```\n![코드](image:998)\n```\n\n" + "\\![문자](image:997)\n\n![닫는 \\] 괄호](image:7)";
        assertThat(parser.parse(markdown)).containsExactly(7L);
    }

    @Test
    @DisplayName("잘못된 이미지 ID와 10개를 초과한 서로 다른 참조는 거절한다")
    void rejectsInvalidOrTooManyImages() {
        String tooMany
            = IntStream.rangeClosed(1, 11).mapToObj(id -> "![x](image:" + id + ")").collect(Collectors.joining(" "));
        for (String markdown : new String[] {
            "![x](image:abc)",
            "![x](image:0)",
            "![x](https://example.com/image.png)",
            tooMany
        }) {
            assertThatThrownBy(() -> parser.parse(markdown))
                .isInstanceOfSatisfying(
                    ProblemException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_IMAGE_INVALID)
                );
        }
    }
}

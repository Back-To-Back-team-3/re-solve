package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemRevisionTagTest {

    @Test
    @DisplayName("문제 회차 태그 연결을 생성한다")
    void createsProblemRevisionTag() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 10, 0);

        ProblemRevisionTag link = ProblemRevisionTag.create(11L, 7L, createdAt);

        assertThat(link.getProblemRevisionId()).isEqualTo(11L);
        assertThat(link.getTagId()).isEqualTo(7L);
        assertThat(link.getCreatedAt()).isEqualTo(createdAt);
    }
}

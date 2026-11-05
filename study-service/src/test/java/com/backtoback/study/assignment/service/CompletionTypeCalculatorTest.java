package com.backtoback.study.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.study.assignment.domain.CompletionType;

class CompletionTypeCalculatorTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE_AT = LocalDateTime.of(2026, 11, 7, 23, 59);

    private final CompletionTypeCalculator calculator = new CompletionTypeCalculator();

    @Test
    @DisplayName("제출 접수 시각이 시작 전이면 PRE_SOLVED를 반환한다")
    void calculate_beforeStartsAt_returnsPreSolved() {
        LocalDateTime beforeStart = STARTS_AT.minusMinutes(1);
        assertThat(calculator.calculate(beforeStart, STARTS_AT, DEADLINE_AT)).isEqualTo(CompletionType.PRE_SOLVED);
    }

    @Test
    @DisplayName("제출 접수 시각이 시작·마감 사이이면 ON_TIME을 반환한다")
    void calculate_betweenStartsAndDeadline_returnsOnTime() {
        LocalDateTime between = STARTS_AT.plusDays(1);
        assertThat(calculator.calculate(between, STARTS_AT, DEADLINE_AT)).isEqualTo(CompletionType.ON_TIME);
    }

    @Test
    @DisplayName("제출 접수 시각이 마감 정각이면 ON_TIME을 반환한다")
    void calculate_atDeadline_returnsOnTime() {
        assertThat(calculator.calculate(DEADLINE_AT, STARTS_AT, DEADLINE_AT)).isEqualTo(CompletionType.ON_TIME);
    }

    @Test
    @DisplayName("제출 접수 시각이 마감 후이면 LATE를 반환한다")
    void calculate_afterDeadline_returnsLate() {
        LocalDateTime afterDeadline = DEADLINE_AT.plusMinutes(1);
        assertThat(calculator.calculate(afterDeadline, STARTS_AT, DEADLINE_AT)).isEqualTo(CompletionType.LATE);
    }

    @Test
    @DisplayName("마감이 없으면 시작 이후 제출은 모두 ON_TIME을 반환한다")
    void calculate_nullDeadline_returnsOnTime() {
        LocalDateTime anyTime = STARTS_AT.plusDays(100);
        assertThat(calculator.calculate(anyTime, STARTS_AT, null)).isEqualTo(CompletionType.ON_TIME);
    }
}

package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.backtoback.judge.judging.domain.JudgeVerdict;

class SubmissionVerdictTest {
    @ParameterizedTest
    @MethodSource("completed")
    @DisplayName("전체 완료 결과는 CE 우선과 고정 순서 첫 비AC로 집계한다")
    void summarizes(List<JudgeVerdict> values, JudgeVerdict expected) {
        assertThat(SubmissionVerdict.summarize(values)).isEqualTo(expected);
    }

    static Stream<Arguments> completed() {
        return Stream
            .of(
                Arguments.of(List.of(JudgeVerdict.AC, JudgeVerdict.AC), JudgeVerdict.AC),
                Arguments.of(List.of(JudgeVerdict.AC, JudgeVerdict.WA, JudgeVerdict.TLE), JudgeVerdict.WA),
                Arguments.of(List.of(JudgeVerdict.TLE, JudgeVerdict.WA), JudgeVerdict.TLE),
                Arguments.of(List.of(JudgeVerdict.WA, JudgeVerdict.CE), JudgeVerdict.CE)
            );
    }

    @ParameterizedTest
    @MethodSource("unfinished")
    @DisplayName("시스템 오류나 미완료 결과가 있으면 CE가 있어도 확정하지 않는다")
    void rejectsUnfinished(List<JudgeVerdict> values) {
        assertThatThrownBy(() -> SubmissionVerdict.summarize(values)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<List<JudgeVerdict>> unfinished() {
        return Stream
            .of(List.of(), Arrays.asList(JudgeVerdict.CE, null), List.of(JudgeVerdict.CE, JudgeVerdict.SYSTEM_ERROR));
    }
}

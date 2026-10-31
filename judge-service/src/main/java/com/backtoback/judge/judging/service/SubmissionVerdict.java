package com.backtoback.judge.judging.service;

import java.util.List;

import org.springframework.util.Assert;

import com.backtoback.judge.judging.domain.JudgeVerdict;

/** 고정 테스트 순서의 완료 결과를 제출 대표 판정으로 집계한다. (정책 및 상태 §3.3) */
public final class SubmissionVerdict {
    private SubmissionVerdict() {}

    /** 전체 코드 판정이 확정된 경우에만 CE 우선·첫 비AC 규칙을 적용한다. */
    public static JudgeVerdict summarize(List<JudgeVerdict> orderedVerdicts) {
        Assert.notEmpty(orderedVerdicts, "테스트 결과가 필요합니다.");
        Assert
            .isTrue(
                orderedVerdicts.stream().allMatch(value -> value != null && value != JudgeVerdict.SYSTEM_ERROR),
                "미완료·시스템 오류는 대표 판정으로 확정할 수 없습니다."
            );
        if (orderedVerdicts.contains(JudgeVerdict.CE)) {
            return JudgeVerdict.CE;
        }
        return orderedVerdicts.stream().filter(value -> value != JudgeVerdict.AC).findFirst().orElse(JudgeVerdict.AC);
    }
}

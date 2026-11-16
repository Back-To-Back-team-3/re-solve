package com.backtoback.member.skill.dto;

import java.time.Instant;
import java.util.List;

import com.backtoback.member.skill.client.JudgeDiagnosisRunClient.RunResult;
import com.backtoback.member.skill.client.JudgeDiagnosisRunClient.TestResult;

/** 진단 제출 판정과 분리한 공개 예제·직접 입력의 실행 결과다(API 명세서 §1.5.9). */
public record DiagnosisRunResponse(List<TestResult> results, Instant completedAt) {
    /**
     * 검증한 내부 실행 결과를 외부 응답으로 변환한다.
     *
     * @param result 내부 실행 결과
     * @return 원래 입력별 반환값·디버그 출력·오류를 유지한 외부 응답
     */
    public static DiagnosisRunResponse from(RunResult result) {
        return new DiagnosisRunResponse(result.results(), result.completedAt());
    }
}

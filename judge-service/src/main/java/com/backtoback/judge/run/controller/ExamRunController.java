package com.backtoback.judge.run.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.judge.run.dto.ExamRunRequest;
import com.backtoback.judge.run.dto.ExamRunResponse;
import com.backtoback.judge.run.service.ExamRunService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 호출 서비스가 검증한 시험·진단 Run을 공통 내부 경로에서 받는다. */
@RestController
@RequiredArgsConstructor
public class ExamRunController {
    private final ExamRunService runs;

    /**
     * 시험·진단의 실행 결과를 공통 래퍼 없이 반환한다(API 명세서 §3.3.8).
     *
     * @param request 검증할 문맥·고정 회차·실행 코드
     * @return 저장 없는 공개 예제·직접 입력 실행 결과
     * @throws com.backtoback.judge.global.error.JudgeException 입력 검증 또는 실행에 실패한 경우
     */
    @PostMapping("/internal/v1/judge/runs")
    public ExamRunResponse run(@Valid @RequestBody ExamRunRequest request) {
        return runs.run(request);
    }
}

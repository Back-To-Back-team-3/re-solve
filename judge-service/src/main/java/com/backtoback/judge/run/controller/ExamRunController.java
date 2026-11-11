package com.backtoback.judge.run.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.judge.run.dto.ExamRunRequest;
import com.backtoback.judge.run.dto.ExamRunResponse;
import com.backtoback.judge.run.service.ExamRunService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Contest의 시험 Run을 내부 경로에서 받는다. */
@RestController
@RequiredArgsConstructor
public class ExamRunController {
    private final ExamRunService runs;

    /** §3.3.8 성공 응답은 공통 래퍼 없이 반환한다. */
    @PostMapping("/internal/v1/judge/runs")
    public ExamRunResponse run(@Valid @RequestBody ExamRunRequest request) {
        return runs.run(request);
    }
}

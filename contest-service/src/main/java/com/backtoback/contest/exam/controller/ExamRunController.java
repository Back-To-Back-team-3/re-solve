package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.client.ExamRunClient.Result;
import com.backtoback.contest.exam.service.ExamRunService;
import com.backtoback.contest.exam.service.ExamRunService.RunRequest;

import lombok.RequiredArgsConstructor;

/** 시험 참가자의 공개 예제와 사용자 입력 실행을 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamRunController {
    private final ExamRunService service;

    /** 고정 회차의 Run 결과를 반환하며 제출이나 Draft는 저장하지 않는다. */
    @PostMapping("/api/v1/exams/{examId}/problems/{problemId}/runs")
    public ResponseEntity<ApiResponse<Result>> run(
        @PathVariable String examId,
        @PathVariable String problemId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestBody RunRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, service.run(examId, user.userId(), problemId, request)));
    }
}

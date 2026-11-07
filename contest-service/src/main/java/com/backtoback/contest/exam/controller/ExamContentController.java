package com.backtoback.contest.exam.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.service.ExamContentService;
import com.backtoback.contest.exam.service.ExamContentService.ProblemList;

import lombok.RequiredArgsConstructor;

/** 실제 입장자의 시험 문제와 이미지를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamContentController {
    private final ExamContentService service;

    /** 고정 회차 문제와 공개 예제를 반환한다. */
    @GetMapping("/api/v1/exams/{examId}/problems")
    public ResponseEntity<ApiResponse<ProblemList>> problems(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, service.read(examId, user.userId())));
    }

    /** 고정 본문에 속한 이미지의 캐시를 금지해 반환한다. */
    @GetMapping("/api/v1/exams/{examId}/problems/{problemId}/images/{imageId}")
    public ResponseEntity<byte[]> image(
        @PathVariable String examId,
        @PathVariable String problemId,
        @PathVariable String imageId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        var image = service.image(examId, user.userId(), problemId, imageId);
        return ResponseEntity
            .ok()
            .contentType(image.getHeaders().getContentType())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(image.getBody());
    }
}

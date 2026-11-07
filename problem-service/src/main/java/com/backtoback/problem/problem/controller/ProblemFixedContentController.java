package com.backtoback.problem.problem.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.dto.response.ProblemContentResponse;
import com.backtoback.problem.problem.service.ProblemContentService;

import lombok.RequiredArgsConstructor;

/** 호출 서비스가 권한을 확인한 고정 회차 본문과 이미지를 제공한다. */
@RestController
@Profile("!internal-stub")
@ConditionalOnProperty(
    prefix = "problem.internal-api",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@RequiredArgsConstructor
public class ProblemFixedContentController {
    private final ProblemContentService service;

    /**
     * 고정 회차 본문을 조회한다(API 명세서 §2.6.3).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @param context 호출 서비스가 확인한 열람 문맥
     * @return 내부 본문
     */
    @GetMapping("/internal/v1/problems/{problemId}/revisions/{revisionId}/content")
    public ProblemContentResponse content(
        @PathVariable long problemId,
        @PathVariable long revisionId,
        @RequestHeader("X-Access-Context") String context
    ) {
        return ProblemContentResponse.from(service.read(problemId, revisionId, context));
    }

    /**
     * 고정 회차의 연결 이미지를 조회한다(API 명세서 §2.6.4).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @param imageId 이미지
     * @param context 호출 서비스의 열람 문맥
     * @return 실제 MIME의 이미지
     */
    @GetMapping("/internal/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}")
    public ResponseEntity<byte[]> image(
        @PathVariable long problemId,
        @PathVariable long revisionId,
        @PathVariable long imageId,
        @RequestHeader("X-Access-Context") String context
    ) {
        var image = service.image(problemId, revisionId, imageId, context);
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(image.bytes());
    }
}

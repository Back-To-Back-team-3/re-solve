package com.backtoback.problem.problem.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.problem.dto.response.ProblemContentResponse;
import com.backtoback.problem.problem.dto.response.ProblemDetailResponse;
import com.backtoback.problem.problem.service.ProblemContentService;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemReadStore;

import lombok.RequiredArgsConstructor;

/** 로그인 회원에게 공개 문제의 본문과 연결 이미지를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ProblemContentController {
    private final ProblemContentService service;
    private final ProblemReadStore reads;
    private final ProblemCreateBodyReader identities;

    /**
     * 공개 사용 회차 본문을 조회한다(API 명세서 §2.1.2).
     *
     * @param problemId 문제
     * @param user 회원
     * @return 일반 상세
     */
    @GetMapping("/api/v1/problems/{problemId}")
    public ResponseEntity<ApiResponse<ProblemDetailResponse>> content(
        @PathVariable long problemId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        var content = ProblemContentResponse.from(service.read(problemId, null, null));
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        ProblemDetailResponse.from(content, reads.solved(problemId, identities.authorId(user.userId())))
                    )
            );
    }

    /**
     * 공개 문제의 지정 회차 연결 이미지를 조회한다(API 명세서 §2.1.3).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @param imageId 이미지
     * @return 캐시하지 않는 이미지
     */
    @GetMapping("/api/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}")
    public ResponseEntity<byte[]> image(
        @PathVariable long problemId,
        @PathVariable long revisionId,
        @PathVariable long imageId
    ) {
        var image = service.image(problemId, revisionId, imageId, null);
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(image.bytes());
    }
}

package com.backtoback.study.recommendation.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.recommendation.dto.StudyRecommendationPageResponse;
import com.backtoback.study.recommendation.service.StudyRecommendationService;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 추천 조회 API (API 명세서 5.1.4 추천 스터디).
 */
@RestController
@RequestMapping("/api/v1/studies")
@RequiredArgsConstructor
public class StudyRecommendationController {

    // API 명세서 공통 규칙 "size 기본 20·최대 100".
    private static final int MAX_PAGE_SIZE = 100;

    private final StudyRecommendationService studyRecommendationService;

    /**
     * 5.1.4 추천 스터디 — 로그인이 필요하다. 정렬은 서버에서 고정하므로 {@code sort} 파라미터는 무시한다.
     */
    @GetMapping("/recommendations")
    public ResponseEntity<ApiResponse<StudyRecommendationPageResponse>> getRecommendations(
        @AuthenticationPrincipal GatewayUser user,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        Long memberId = Long.parseLong(user.userId());
        StudyRecommendationPageResponse response
            = studyRecommendationService.getRecommendations(memberId, toUnsortedPage(pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 네이티브 쿼리에 정렬이 넘어가면 ORDER BY가 덧붙어 고정 정렬이 깨지므로 페이지 번호·크기만 남긴다.
    private Pageable toUnsortedPage(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size);
    }
}

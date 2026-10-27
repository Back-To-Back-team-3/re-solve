package com.backtoback.study.discovery.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.discovery.service.StudyQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 탐색 조회 API (API 명세서 5. 스터디 > 5.1 외부 API — 스터디 탐색·조회).
 */
@RestController
@RequestMapping("/api/v1/studies")
@RequiredArgsConstructor
public class StudyController {

    private static final int MAX_PAGE_SIZE = 100;

    private final StudyQueryService studyQueryService;

    /**
     * 5.1.1 스터디 목록 조회 — 모집 중인 스터디를 조건으로 검색한다. 인증 없이 호출할 수 있다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<StudySummaryResponse>>> searchStudies(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String goal,
        @RequestParam(required = false) String skillLevel,
        @RequestParam(required = false) String preferredLanguage,
        @RequestParam(required = false) String activeTimeSlot,
        @PageableDefault(
            size = 20,
            sort = "createdAt"
        ) Pageable pageable
    ) {
        Pageable safePageable = clampPageSize(pageable);
        var result
            = studyQueryService
                .searchStudies(keyword, goal, skillLevel, preferredLanguage, activeTimeSlot, safePageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    // 요청 size가 상한을 넘으면 낮춘다(API 명세서 5.1.1 size 최대 100).
    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}

package com.backtoback.problem.tag.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.problem.tag.dto.response.TagResponse;
import com.backtoback.problem.tag.service.TagService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 인증 사용자의 태그 목록 조회 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;

    /**
     * API 명세서 2.5.1 태그 목록 조회 — 검색·페이지·정렬 조건을 검증한다.
     *
     * @param keyword 태그 이름에 포함된 검색어; 빈 문자열이면 전체 조회
     * @param page 0부터 21,474,836까지 허용하는 페이지 번호; 최대 페이지 크기에서도 JPA 조회 위치의 {@code int} 범위를 유지한다
     * @param size 페이지 크기; 1부터 100까지 허용
     * @param sort {@code displayOrder} 또는 {@code name}과 {@code asc} 또는 {@code desc}의 조합
     */
    @GetMapping("/api/v1/problems/tags")
    public ResponseEntity<ApiResponse<PageResponse<TagResponse>>> find(
        @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
        @RequestParam(defaultValue = "0") @Min(0) @Max(Integer.MAX_VALUE / 100) int page,
        @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "displayOrder,asc") @Pattern(regexp = "(displayOrder|name),(asc|desc)") String sort
    ) {
        return ResponseEntity.ok(ApiResponse.success(tagService.find(keyword, page, size, sort)));
    }
}

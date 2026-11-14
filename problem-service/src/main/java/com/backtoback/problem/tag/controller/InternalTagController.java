package com.backtoback.problem.tag.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.tag.dto.response.TagExistsResponse;
import com.backtoback.problem.tag.service.TagService;

import lombok.RequiredArgsConstructor;

/** 다른 서비스가 호출하는 태그 내부 API 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
public class InternalTagController {
    private final TagService tagService;

    /**
     * API 명세서 2.6.11 태그 존재 확인 — member가 태그 진입 전에 호출한다.
     * 내부 API는 공통 응답 형식 없이 데이터 객체를 그대로 반환한다(API 명세서 0.3).
     *
     * @param tagId 존재를 확인할 태그 ID
     * @return 태그의 문자열 ID·이름
     */
    @GetMapping("/internal/v1/tags/{tagId}")
    public ResponseEntity<TagExistsResponse> getExisting(@PathVariable long tagId) {
        return ResponseEntity.ok(tagService.getExisting(tagId));
    }
}

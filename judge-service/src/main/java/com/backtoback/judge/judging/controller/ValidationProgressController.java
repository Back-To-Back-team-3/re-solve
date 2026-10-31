package com.backtoback.judge.judging.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.ValidationProgressResponse;
import com.backtoback.judge.judging.service.ValidationProgressService;

import lombok.RequiredArgsConstructor;

/** 내부 네트워크의 Problem에 검증 진행 요약을 제공한다. */
@RestController
@RequiredArgsConstructor
public class ValidationProgressController {
    private final ValidationProgressService service;

    /**
     * 3.3.6 검증 진행·결과 조회 — 내부 성공 응답은 공통 래퍼 없이 제공한다.
     *
     * @param validationId 검증 작업 ID
     * @return 진행 및 최종 판정 요약
     * @throws JudgeException {@code PROBLEM_VALIDATION_NOT_FOUND}: 작업 미생성;
     *     {@code COMMON_INVALID_REQUEST}: 양수가 아닌 검증 ID
     */
    @GetMapping("/internal/v1/judge/validations/{validationId}")
    public ValidationProgressResponse find(@PathVariable long validationId) {
        return service.find(validationId);
    }
}

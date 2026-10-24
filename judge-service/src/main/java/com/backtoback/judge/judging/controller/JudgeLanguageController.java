package com.backtoback.judge.judging.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.judge.judging.dto.response.JudgeLanguagesResponse;
import com.backtoback.judge.judging.service.JudgeLanguageService;

import lombok.RequiredArgsConstructor;

/**
 * 로그인 사용자에게 서비스 전체의 언어 지원 상태를 제공한다.
 */
@RestController
@RequiredArgsConstructor
public class JudgeLanguageController {
    private final JudgeLanguageService languageService;

    /**
     * 3.1.1 지원 언어 조회 — 로그인 사용자에게 지원 언어와 활성화 상태를 응답한다. (API 명세서 3.1.1)
     *
     * @return 지원 언어 목록
     */
    @GetMapping("/api/v1/judge/languages")
    public ResponseEntity<ApiResponse<JudgeLanguagesResponse>> findAll() {
        return ResponseEntity.ok(ApiResponse.success(JudgeLanguagesResponse.from(languageService.findAll())));
    }
}

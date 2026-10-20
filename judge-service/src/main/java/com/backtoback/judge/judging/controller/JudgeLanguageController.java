package com.backtoback.judge.judging.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.judge.judging.dto.response.JudgeLanguagesResponse;
import com.backtoback.judge.judging.service.JudgeLanguageService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class JudgeLanguageController {
    private final JudgeLanguageService languageService;

    /**
     * 로그인한 사용자에게 지원 언어와 활성화 상태를 응답한다.
     *
     * @return 지원 언어 목록
     */
    @GetMapping("/api/v1/judge/languages")
    public ApiResponse<JudgeLanguagesResponse> findAll() {
        return ApiResponse.success(languageService.findAll());
    }
}

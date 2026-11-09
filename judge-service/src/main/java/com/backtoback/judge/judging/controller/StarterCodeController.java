package com.backtoback.judge.judging.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.StarterCodeRequest;
import com.backtoback.judge.judging.dto.StarterCodeResponse;
import com.backtoback.judge.judging.service.StarterCodeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 내부 네트워크의 Problem에 통과 언어의 기본 코드를 제공한다. */
@RestController
@RequiredArgsConstructor
public class StarterCodeController {
    private final StarterCodeService service;

    /**
     * 3.3.5 기본 코드 생성 — 공통 래퍼 없이 생성 결과를 반환한다.
     *
     * @param request 고정 회차 명세와 통과 언어
     * @return 요청 언어별 기본 코드
     * @throws JudgeException 잘못된 함수 명세·언어이면 {@code COMMON_INVALID_REQUEST}
     */
    @PostMapping("/internal/v1/judge/starter-codes")
    public StarterCodeResponse generate(@Valid @RequestBody StarterCodeRequest request) {
        return StarterCodeResponse.from(service.generate(request.functionSpec(), request.languages()));
    }
}

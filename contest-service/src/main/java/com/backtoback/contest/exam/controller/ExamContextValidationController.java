package com.backtoback.contest.exam.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.dto.ContextValidationResponse;
import com.backtoback.contest.exam.service.ExamContextValidationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 실제 시험 문맥의 내부 열람·Run 권한 검증을 제공한다. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "contest.internal-http.mode",
    havingValue = "real"
)
public class ExamContextValidationController {
    private final ExamContextValidationService service;

    /** API 명세서 §4.7.1의 EXAM 문맥을 실제 저장 상태로 확인한다. */
    @PostMapping("/internal/v1/contest/contexts/validate")
    public ResponseEntity<ContextValidationResponse> validate(@Valid @RequestBody ContextValidateRequest request) {
        return ResponseEntity.ok(service.validate(request));
    }
}

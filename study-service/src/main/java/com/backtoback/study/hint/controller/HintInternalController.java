package com.backtoback.study.hint.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.study.hint.service.HintCommandService;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 내부 API (api-spec §6.2) — ai-service 전용 호출.
 */
@RestController
@RequestMapping("/internal/v1/hint-requests")
@RequiredArgsConstructor
public class HintInternalController {

    private final HintCommandService hintCommandService;

    /**
     * 6.2.1 힌트 요청 GENERATING 전이.
     */
    @PatchMapping("/{hintRequestId}/generating")
    public ResponseEntity<ApiResponse<Void>> transitionToGenerating(@PathVariable Long hintRequestId) {
        hintCommandService.transitionToGenerating(hintRequestId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}

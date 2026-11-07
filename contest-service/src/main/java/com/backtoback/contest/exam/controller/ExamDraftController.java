package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.service.ExamDraftBuffer;
import com.backtoback.contest.exam.service.ExamDraftStore;
import com.backtoback.contest.exam.service.ExamDraftStore.Recovery;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveResponse;

import lombok.RequiredArgsConstructor;

/** 시험 참가자의 Draft 저장과 종료 후 복구 조회를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamDraftController {
    private final ExamDraftBuffer buffer;
    private final ExamDraftStore store;

    /** 초당 버퍼를 거쳐 DB에 커밋된 저장 결과를 반환한다. */
    @PutMapping("/api/v1/exams/{examId}/drafts/{problemId}")
    public ResponseEntity<ApiResponse<SaveResponse>> save(
        @PathVariable String examId,
        @PathVariable String problemId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestBody SaveRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, buffer.save(examId, user.userId(), problemId, request)));
    }

    /** 실제 입장한 본인의 서버 Draft와 문제별 최대 순번을 반환한다. */
    @GetMapping("/api/v1/exams/{examId}/drafts/me")
    public ResponseEntity<ApiResponse<Recovery>> read(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, store.read(examId, user.userId())));
    }
}

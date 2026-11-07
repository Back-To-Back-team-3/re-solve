package com.backtoback.contest.exam.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.service.ExamEventStreamService;

import lombok.RequiredArgsConstructor;

/** 시험 참가자의 상태·결과 변경 구독을 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamEventController {
    private final ExamEventStreamService streams;

    /** API 명세서 §4.3.1의 서버 이벤트 스트림을 연다. */
    @GetMapping(
        value = "/api/v1/exams/{examId}/events",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<SseEmitter> subscribe(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader(
            value = "Last-Event-ID",
            required = false
        ) String lastEventId
    ) {
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore().cachePrivate())
            .body(streams.subscribe(examId, user.userId(), lastEventId));
    }
}

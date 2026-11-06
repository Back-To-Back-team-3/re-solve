package com.backtoback.problem.admin.controller;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.problem.dto.request.ProblemPublicationRequest;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemPublicationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 작성 관리자의 문제 수동 공개 API를 제공한다. */
@RestController
@RequiredArgsConstructor
public class AdminProblemPublicationController {
    private final ProblemPublicationService service;
    private final ProblemCreateBodyReader identities;

    /**
     * 사용 가능한 일반 문제를 공개한다. API 명세서 §2.3.7을 따른다.
     *
     * @param problemId 대상 문제
     * @param user Gateway 인증 관리자
     * @param key 원래 요청 멱등키
     * @param body 기대 버전
     * @param request 감사 주소를 읽을 HTTP 요청
     * @return 최초 공개 성공 또는 확정 실패 응답
     */
    @PostMapping("/api/v1/admin/problems/{problemId}/publication")
    public ResponseEntity<String> publish(
        @PathVariable long problemId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody ProblemPublicationRequest body,
        HttpServletRequest request
    ) {
        var result
            = service
                .publish(
                    problemId,
                    identities.authorId(user.userId()),
                    key,
                    body,
                    MDC.get(CorrelationIdFilter.MDC_KEY),
                    request.getRemoteAddr()
                );
        // API §2.3.7의 최초 응답 보존을 위해 공통 래퍼가 포함된 저장 본문을 재구성하지 않는다.
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.json());
    }
}

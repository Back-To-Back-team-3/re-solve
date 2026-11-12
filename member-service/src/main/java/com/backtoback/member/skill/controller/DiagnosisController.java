package com.backtoback.member.skill.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.member.skill.dto.DiagnosisDetailResponse;
import com.backtoback.member.skill.dto.DiagnosisDraftRequest;
import com.backtoback.member.skill.dto.DiagnosisDraftResponse;
import com.backtoback.member.skill.dto.DiagnosisHistoryItem;
import com.backtoback.member.skill.dto.DiagnosisStartRequest;
import com.backtoback.member.skill.dto.DiagnosisStartResponse;
import com.backtoback.member.skill.service.DiagnosisCooldownException;
import com.backtoback.member.skill.service.DiagnosisService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/diagnoses")
@RequiredArgsConstructor
public class DiagnosisController {

    private static final String SORT_PROPERTY = "startedAt";
    private static final int MAX_PAGE_SIZE = 100;

    private final DiagnosisService diagnosisService;

    /**
     * 1.5.1 진단 시작
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DiagnosisStartResponse>> start(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody DiagnosisStartRequest request
    ) {
        DiagnosisStartResponse response
            = DiagnosisStartResponse.from(diagnosisService.start(memberId(user), Long.valueOf(request.tagId())));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("진단을 시작합니다.", response));
    }

    /**
     * 1.5.7 진단 이력 — 허용 정렬 필드는 {@code startedAt}뿐이다(기본 {@code startedAt,desc}).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DiagnosisHistoryItem>>> getHistory(
        @AuthenticationPrincipal GatewayUser user,
        @RequestParam(required = false) Long tagId,
        @PageableDefault(
            size = 20,
            sort = SORT_PROPERTY,
            direction = Sort.Direction.DESC
        ) Pageable pageable
    ) {
        PageResponse<DiagnosisHistoryItem> response
            = PageResponse
                .from(
                    diagnosisService
                        .getHistory(memberId(user), tagId, restrictSort(pageable))
                        .map(DiagnosisHistoryItem::from)
                );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 1.5.2 진단 상세·복구
     */
    @GetMapping("/{diagnosisId}")
    public ResponseEntity<ApiResponse<DiagnosisDetailResponse>> getDetail(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable Long diagnosisId
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(DiagnosisDetailResponse.from(diagnosisService.getDetail(memberId(user), diagnosisId)))
            );
    }

    /**
     * 1.5.3 진단 문제 본문 — problem 고정 회차 본문(2.6.3)을 그대로 준다.
     */
    @GetMapping("/{diagnosisId}/problems/{problemId}")
    public ResponseEntity<ApiResponse<JsonNode>> getProblemContent(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable Long diagnosisId,
        @PathVariable Long problemId
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(diagnosisService.getProblemContent(memberId(user), diagnosisId, problemId)));
    }

    /**
     * 1.5.4 진단 Draft 저장
     */
    @PutMapping("/{diagnosisId}/problems/{problemId}/draft")
    public ResponseEntity<ApiResponse<DiagnosisDraftResponse>> saveDraft(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable Long diagnosisId,
        @PathVariable Long problemId,
        @Valid @RequestBody DiagnosisDraftRequest request
    ) {
        DiagnosisDraftResponse response
            = DiagnosisDraftResponse
                .from(
                    diagnosisService
                        .saveDraft(
                            memberId(user),
                            diagnosisId,
                            problemId,
                            request.language(),
                            request.sourceCode(),
                            Long.parseLong(request.seq())
                        )
                );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 쿨타임 중이면 공통 오류 형식에 {@code Retry-After}(초)를 더한다(API 명세서 1.5.1).
     */
    @ExceptionHandler(DiagnosisCooldownException.class)
    public ResponseEntity<ApiErrorResponse> handleCooldown(DiagnosisCooldownException exception) {
        return ResponseEntity
            .status(exception.getErrorCode().getStatus())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
            .body(ApiErrorResponse.of(exception.getErrorCode()));
    }

    private Pageable restrictSort(Pageable pageable) {
        Sort.Order requested = pageable.getSort().getOrderFor(SORT_PROPERTY);
        Sort.Direction direction = requested == null ? Sort.Direction.DESC : requested.getDirection();
        return PageRequest
            .of(
                pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                Sort.by(direction, SORT_PROPERTY, "id")
            );
    }

    // Gateway가 JWT subject(회원 ID 숫자)를 X-User-Id로 전달한다.
    private Long memberId(GatewayUser user) {
        return Long.valueOf(user.userId());
    }
}

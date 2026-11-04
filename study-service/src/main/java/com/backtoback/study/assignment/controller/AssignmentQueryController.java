package com.backtoback.study.assignment.controller;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.dto.AssignmentDetailResponse;
import com.backtoback.study.assignment.dto.AssignmentSummaryResponse;
import com.backtoback.study.assignment.service.AssignmentQueryService;
import com.backtoback.study.global.error.StudyException;

import lombok.RequiredArgsConstructor;

/**
 * 문제집 조회 API (API 명세서 §5.4.2·§5.4.3).
 */
@RestController
@RequestMapping("/api/v1/studies/{studyId}/assignments")
@RequiredArgsConstructor
public class AssignmentQueryController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("startsAt", "deadlineAt");

    private final AssignmentQueryService assignmentQueryService;

    /**
     * 5.4.2 문제집 목록 조회 — APPROVED 구성원만 호출할 수 있다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AssignmentSummaryResponse>>> listAssignments(
        @PathVariable Long studyId,
        @RequestParam(required = false) AssignmentStatus status,
        @PageableDefault(size = 20) Pageable pageable,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        Pageable safePageable = clampPageSize(resolveSort(pageable));
        Page<AssignmentSummaryResponse> result
            = assignmentQueryService.listAssignments(studyId, memberId, status, safePageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    /**
     * 5.4.3 문제집 상세·진행률 조회 — APPROVED 구성원만 호출할 수 있다.
     */
    @GetMapping("/{assignmentId}")
    public ResponseEntity<ApiResponse<AssignmentDetailResponse>> getAssignmentDetail(
        @PathVariable Long studyId,
        @PathVariable Long assignmentId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        AssignmentDetailResponse response = assignmentQueryService.getAssignmentDetail(studyId, memberId, assignmentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private Pageable resolveSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            Sort defaultSort
                = Sort.by(Sort.Order.asc("deadlineAt").nullsLast()).and(Sort.by(Sort.Direction.ASC, "startsAt"));
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
        }
        boolean hasInvalidSort
            = pageable.getSort().stream().anyMatch(order -> !ALLOWED_SORT_PROPERTIES.contains(order.getProperty()));
        if (hasInvalidSort) {
            throw new StudyException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return pageable;
    }

    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }
}

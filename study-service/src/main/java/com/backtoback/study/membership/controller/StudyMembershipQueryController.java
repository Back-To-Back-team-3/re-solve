package com.backtoback.study.membership.controller;

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
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.dto.MembershipListItem;
import com.backtoback.study.membership.service.StudyMembershipQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 구성원·대기 신청 목록 조회 API (API 명세서 §5.2.1).
 */
@RestController
@RequestMapping("/api/v1/studies/{studyId}/memberships")
@RequiredArgsConstructor
public class StudyMembershipQueryController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("joinedAt", "appliedAt");

    private final StudyMembershipQueryService studyMembershipQueryService;

    /**
     * 5.2.4 구성원·대기 신청 목록 조회.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MembershipListItem>>> findMemberships(
        @PathVariable Long studyId,
        @RequestParam(defaultValue = "APPROVED") MembershipStatus status,
        @PageableDefault(size = 20) Pageable pageable,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        Pageable safePageable = clampPageSize(resolveSort(pageable, status));
        Page<MembershipListItem> result
            = studyMembershipQueryService.findMemberships(studyId, memberId, status, safePageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    private Pageable resolveSort(Pageable pageable, MembershipStatus status) {
        if (pageable.getSort().isUnsorted()) {
            Sort defaultSort
                = status == MembershipStatus.PENDING ? Sort.by(Sort.Order.asc("appliedAt"))
                    : Sort.by(Sort.Order.asc("joinedAt"));
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
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}

package com.backtoback.member.admin.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.member.admin.dto.AdminMemberResponse;
import com.backtoback.member.admin.service.AdminMemberService;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 회원 API. {@code ADMIN} 권한은 {@code SecurityConfig}의 {@code /api/v1/admin/**} 규칙이 확인한다.
 */
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String SORT_PROPERTY = "createdAt";

    private final AdminMemberService adminMemberService;

    /**
     * 1.6.1 회원 목록 조회 — 허용 정렬 필드는 {@code createdAt}뿐이다(기본 {@code createdAt,desc}).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminMemberResponse>>> searchMembers(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) MemberStatus status,
        @RequestParam(required = false) MemberRole role,
        @PageableDefault(
            size = 20,
            sort = SORT_PROPERTY,
            direction = Sort.Direction.DESC
        ) Pageable pageable
    ) {
        PageResponse<AdminMemberResponse> response
            = PageResponse
                .from(
                    adminMemberService
                        .searchMembers(keyword, status, role, restrictSort(pageable))
                        .map(AdminMemberResponse::from)
                );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // createdAt 외 정렬은 버리고, 같은 가입 시각은 id로 순서를 고정한다. 크기는 100으로 제한한다.
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
}

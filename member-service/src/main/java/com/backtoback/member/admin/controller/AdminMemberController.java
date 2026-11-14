package com.backtoback.member.admin.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.member.admin.dto.AdminMemberResponse;
import com.backtoback.member.admin.dto.MemberRoleChangeRequest;
import com.backtoback.member.admin.dto.MemberRoleChangeResponse;
import com.backtoback.member.admin.dto.MemberStatusChangeRequest;
import com.backtoback.member.admin.dto.MemberStatusChangeResponse;
import com.backtoback.member.admin.service.AdminMemberService;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_CLIENT_IP_LENGTH = 45;

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

    /**
     * 1.6.2 회원 정지·해제 — 사유와 집행자를 감사 로그에 남긴다.
     */
    @PatchMapping("/{memberId}/status")
    public ResponseEntity<ApiResponse<MemberStatusChangeResponse>> changeStatus(
        @AuthenticationPrincipal GatewayUser admin,
        @PathVariable Long memberId,
        @Valid @RequestBody MemberStatusChangeRequest request,
        HttpServletRequest httpRequest
    ) {
        MemberStatusChangeResponse response
            = MemberStatusChangeResponse
                .from(
                    adminMemberService
                        .changeStatus(
                            actorId(admin),
                            memberId,
                            request.status(),
                            request.reason(),
                            clientIp(httpRequest)
                        )
                );
        return ResponseEntity.ok(ApiResponse.success("회원 상태가 변경되었습니다.", response));
    }

    /**
     * 1.6.3 회원 권한 변경 — 본인 권한은 바꿀 수 없다.
     */
    @PatchMapping("/{memberId}/role")
    public ResponseEntity<ApiResponse<MemberRoleChangeResponse>> changeRole(
        @AuthenticationPrincipal GatewayUser admin,
        @PathVariable Long memberId,
        @Valid @RequestBody MemberRoleChangeRequest request,
        HttpServletRequest httpRequest
    ) {
        MemberRoleChangeResponse response
            = MemberRoleChangeResponse
                .from(
                    adminMemberService
                        .changeRole(actorId(admin), memberId, request.role(), request.reason(), clientIp(httpRequest))
                );
        return ResponseEntity.ok(ApiResponse.success("권한이 변경되었습니다.", response));
    }

    // Gateway가 JWT subject(회원 ID 숫자)를 X-User-Id로 전달한다.
    private Long actorId(GatewayUser admin) {
        return Long.valueOf(admin.userId());
    }

    // Gateway를 거치면 원래 클라이언트 주소는 X-Forwarded-For의 첫 값이다. 감사 기록용이라 검증하지 않고 길이만 자른다.
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        String ip
            = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].strip();
        return ip.length() > MAX_CLIENT_IP_LENGTH ? ip.substring(0, MAX_CLIENT_IP_LENGTH) : ip;
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

package com.backtoback.study.discovery.controller;

import java.util.Set;

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
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.discovery.dto.StudyDetailResponse;
import com.backtoback.study.discovery.dto.StudyMyListResponse;
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.discovery.service.StudyQueryService;
import com.backtoback.study.membership.domain.MembershipStatus;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 탐색 조회 API (API 명세서 5. 스터디 > 5.1 외부 API — 스터디 탐색·조회).
 */
@RestController
@RequestMapping("/api/v1/studies")
@RequiredArgsConstructor
public class StudyController {

    private static final int MAX_PAGE_SIZE = 100;

    // API 명세서 5.1.1 "허용 정렬 필드"에 없는 값으로 정렬을 요청하면 저장소 조회가 실패할 수 있어 미리 걸러낸다.
    private static final Set<String> SEARCH_SORT_PROPERTIES = Set.of("createdAt", "memberCount", "lastActivityAt");

    private final StudyQueryService studyQueryService;

    /**
     * 5.1.1 스터디 목록 조회 — 모집 중인 스터디를 조건으로 검색한다. 인증 없이 호출할 수 있다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<StudySummaryResponse>>> searchStudies(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String goal,
        @RequestParam(required = false) String skillLevel,
        @RequestParam(required = false) String preferredLanguage,
        @RequestParam(required = false) String activeTimeSlot,
        @PageableDefault(
            size = 20,
            sort = "createdAt",
            direction = Sort.Direction.DESC
        ) Pageable pageable
    ) {
        Pageable safePageable = clampPageSize(restrictToAllowedSort(pageable, SEARCH_SORT_PROPERTIES));
        var result
            = studyQueryService
                .searchStudies(keyword, goal, skillLevel, preferredLanguage, activeTimeSlot, safePageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(result)));
    }

    /**
     * 5.1.2 스터디 상세 — 비구성원도 조회할 수 있다. 로그인 시 {@code myMembership}을 함께 반환한다.
     */
    @GetMapping("/{studyId}")
    public ResponseEntity<ApiResponse<StudyDetailResponse>> getStudyDetail(
        @PathVariable Long studyId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = user == null ? null : Long.parseLong(user.userId());
        StudyDetailResponse response = studyQueryService.getStudyDetail(studyId, memberId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 5.1.3 내 스터디 목록 — 로그인이 필요하다. 본인의 {@code PENDING}·{@code APPROVED} 가입과 참여 카운터를 반환한다.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<StudyMyListResponse>> getMyStudies(
        @RequestParam(required = false) MembershipStatus status,
        @AuthenticationPrincipal GatewayUser user,
        @PageableDefault(
            size = 20,
            sort = "joinedAt"
        ) Pageable pageable
    ) {
        Long memberId = Long.parseLong(user.userId());
        Pageable safePageable = clampPageSize(pageable);
        StudyMyListResponse response = studyQueryService.getMyStudies(memberId, status, safePageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 요청 size가 상한을 넘으면 낮춘다(API 명세서 5.1.1 size 최대 100). 5.1.3도 같은 한도를 적용한다.
    private Pageable clampPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }

    // 허용되지 않은 정렬 필드는 제거하고, 남는 조건이 없으면 기본 정렬(createdAt desc)로 되돌린다.
    private Pageable restrictToAllowedSort(Pageable pageable, Set<String> allowedProperties) {
        Sort allowedSort
            = Sort.by(pageable.getSort().filter(order -> allowedProperties.contains(order.getProperty())).toList());
        Sort sort = allowedSort.isUnsorted() ? Sort.by(Sort.Order.desc("createdAt")) : allowedSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
